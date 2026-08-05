package com.catchzoon.network.monitor

import com.catchzoon.network.core.NetworkEvent
import com.catchzoon.network.core.NetworkEventListener
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkResponseSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 根据最近请求质量输出的轻量网络健康等级。 */
public enum class NetworkQuality { UNKNOWN, GOOD, DEGRADED, OFFLINE }

/** 不包含 URL 参数、请求头和正文的聚合网络指标。 */
public data class NetworkMetricsSnapshot(
    val totalRequests: Long = 0L,
    val inFlightRequests: Int = 0,
    val successfulRequests: Long = 0L,
    val failedRequests: Long = 0L,
    val cancelledRequests: Long = 0L,
    val cacheHits: Long = 0L,
    val staleCacheHits: Long = 0L,
    val averageDurationMillis: Long = 0L,
    val p95DurationMillis: Long = 0L,
    val failuresByCategory: Map<NetworkFailureCategory, Long> = emptyMap(),
    val quality: NetworkQuality = NetworkQuality.UNKNOWN,
)

/**
 * 进程内滚动指标收集器，可直接注册到 NetworkClient 并由页面或监控 SDK 收集 [state]。
 *
 * 只保留有限数量的结果样本，长期运行不会无限增长内存。
 */
public class NetworkMetricsCollector(
    private val maxRecentSamples: Int = DEFAULT_MAX_SAMPLES,
    private val degradedP95Millis: Long = DEFAULT_DEGRADED_P95_MILLIS,
) : NetworkEventListener {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(NetworkMetricsSnapshot())
    private val recentSamples = ArrayDeque<QualitySample>()
    private val failureCounts = mutableMapOf<NetworkFailureCategory, Long>()

    public val state: StateFlow<NetworkMetricsSnapshot> = mutableState.asStateFlow()

    init {
        require(maxRecentSamples in 10..2_048) { "maxRecentSamples 必须在 10..2048 之间" }
        require(degradedP95Millis in 100L..60_000L) { "degradedP95Millis 必须在 100..60000 之间" }
    }

    override suspend fun onEvent(event: NetworkEvent) {
        mutex.withLock {
            val current = mutableState.value
            mutableState.value = when (event) {
                is NetworkEvent.Started -> current.copy(
                    totalRequests = current.totalRequests + 1,
                    inFlightRequests = current.inFlightRequests + 1,
                )
                is NetworkEvent.Completed -> {
                    val successful = event.statusCode in 200..299
                    if (!successful) {
                        failureCounts[NetworkFailureCategory.HTTP] =
                            failureCounts.getOrElse(NetworkFailureCategory.HTTP) { 0L } + 1L
                    }
                    addSample(
                        QualitySample(
                            success = event.statusCode < 500,
                            durationMillis = event.durationMillis,
                            failureCategory = NetworkFailureCategory.HTTP.takeIf { event.statusCode >= 500 },
                        ),
                    )
                    current.copy(
                        inFlightRequests = (current.inFlightRequests - 1).coerceAtLeast(0),
                        successfulRequests = current.successfulRequests + if (successful) 1 else 0,
                        failedRequests = current.failedRequests + if (successful) 0 else 1,
                        cacheHits = current.cacheHits + if (event.source == NetworkResponseSource.MEMORY_CACHE) 1 else 0,
                        staleCacheHits = current.staleCacheHits + if (event.source == NetworkResponseSource.STALE_CACHE) 1 else 0,
                        failuresByCategory = failureCounts.toMap(),
                    ).withComputedQuality()
                }
                is NetworkEvent.Cancelled -> current.copy(
                    inFlightRequests = (current.inFlightRequests - 1).coerceAtLeast(0),
                    cancelledRequests = current.cancelledRequests + 1,
                )
                is NetworkEvent.Failed -> {
                    failureCounts[event.failure.category] = failureCounts.getOrElse(event.failure.category) { 0L } + 1L
                    addSample(
                        QualitySample(
                            success = false,
                            durationMillis = event.durationMillis,
                            failureCategory = event.failure.category,
                        ),
                    )
                    current.copy(
                        inFlightRequests = (current.inFlightRequests - 1).coerceAtLeast(0),
                        failedRequests = current.failedRequests + 1,
                        failuresByCategory = failureCounts.toMap(),
                    ).withComputedQuality()
                }
            }
        }
    }

    /** 清空滚动样本和累计计数。 */
    public suspend fun reset() {
        mutex.withLock {
            recentSamples.clear()
            failureCounts.clear()
            mutableState.value = NetworkMetricsSnapshot()
        }
    }

    private fun addSample(sample: QualitySample) {
        if (recentSamples.size >= maxRecentSamples) recentSamples.removeFirst()
        recentSamples.addLast(sample)
    }

    private fun NetworkMetricsSnapshot.withComputedQuality(): NetworkMetricsSnapshot {
        if (recentSamples.isEmpty()) return this
        val durations = recentSamples.map(QualitySample::durationMillis).sorted()
        val p95Index = ((durations.size - 1) * 0.95).toInt()
        val p95 = durations[p95Index]
        val average = durations.sum() / durations.size
        val recentOffline = recentSamples.takeLast(OFFLINE_SAMPLE_COUNT).let { samples ->
            samples.size == OFFLINE_SAMPLE_COUNT && samples.all { it.failureCategory in OFFLINE_FAILURES }
        }
        val failureRatio = recentSamples.count { !it.success }.toDouble() / recentSamples.size
        val quality = when {
            recentOffline -> NetworkQuality.OFFLINE
            failureRatio >= DEGRADED_FAILURE_RATIO || p95 >= degradedP95Millis -> NetworkQuality.DEGRADED
            else -> NetworkQuality.GOOD
        }
        return copy(averageDurationMillis = average, p95DurationMillis = p95, quality = quality)
    }
}

private data class QualitySample(
    val success: Boolean,
    val durationMillis: Long,
    val failureCategory: NetworkFailureCategory? = null,
)

private val OFFLINE_FAILURES = setOf(
    NetworkFailureCategory.CONNECTIVITY,
    NetworkFailureCategory.DNS,
    NetworkFailureCategory.TIMEOUT,
)
private const val DEFAULT_MAX_SAMPLES = 100
private const val DEFAULT_DEGRADED_P95_MILLIS = 1_500L
private const val OFFLINE_SAMPLE_COUNT = 3
private const val DEGRADED_FAILURE_RATIO = 0.3
