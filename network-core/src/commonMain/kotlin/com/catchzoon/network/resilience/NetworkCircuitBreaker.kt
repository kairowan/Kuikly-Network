package com.catchzoon.network.resilience

import com.catchzoon.network.core.MonotonicNetworkClock
import com.catchzoon.network.core.NetworkClock
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 按接口组统计连续失败的熔断策略。 */
public data class NetworkCircuitBreakerPolicy(
    val failureThreshold: Int = 5,
    val openDurationMillis: Long = 30_000L,
    val maxTrackedCircuits: Int = 64,
) {
    init {
        require(failureThreshold in 2..20) { "failureThreshold 必须在 2..20 之间" }
        require(openDurationMillis in 1_000L..300_000L) { "openDurationMillis 必须在 1000..300000 之间" }
        require(maxTrackedCircuits in 1..256) { "maxTrackedCircuits 必须在 1..256 之间" }
    }
}

/**
 * 具备关闭、打开和半开探测状态的熔断器。
 *
 * 只统计传输异常和 5xx；业务 4xx、主动取消不会污染服务健康状态。
 */
public class NetworkCircuitBreakerInterceptor(
    private val policy: NetworkCircuitBreakerPolicy = NetworkCircuitBreakerPolicy(),
    private val clock: NetworkClock = MonotonicNetworkClock,
    private val keySelector: (NetworkRawRequest) -> String = ::defaultKey,
) : NetworkInterceptor {
    private val mutex = Mutex()
    private val circuits = linkedMapOf<String, CircuitState>()

    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        val key = keySelector(chain.request).ifBlank(::defaultCircuitKey)
        val probe = beforeRequest(key)
        val response = try {
            chain.proceed()
        } catch (cancelled: CancellationException) {
            releaseProbe(key, probe)
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(key, probe)
            throw failure
        }
        if (response.statusCode >= 500) recordFailure(key, probe) else recordSuccess(key)
        return response
    }

    private suspend fun beforeRequest(key: String): Boolean = mutex.withLock {
        val state = circuits[key] ?: return@withLock false
        val elapsed = clock.nowMillis() - state.openedAtMillis
        if (state.openedAtMillis < 0L) return@withLock false
        if (elapsed < policy.openDurationMillis || state.probeInFlight) {
            throw circuitOpen((policy.openDurationMillis - elapsed).coerceAtLeast(0L))
        }
        state.probeInFlight = true
        true
    }

    private suspend fun recordSuccess(key: String) {
        mutex.withLock { circuits.remove(key) }
    }

    private suspend fun recordFailure(key: String, probe: Boolean) {
        mutex.withLock {
            val state = circuits[key] ?: CircuitState().also {
                if (circuits.size >= policy.maxTrackedCircuits) circuits.remove(circuits.keys.first())
                circuits[key] = it
            }
            state.probeInFlight = false
            state.failureCount++
            if (probe || state.failureCount >= policy.failureThreshold) state.openedAtMillis = clock.nowMillis()
        }
    }

    private suspend fun releaseProbe(key: String, probe: Boolean) {
        if (!probe) return
        mutex.withLock { circuits[key]?.probeInFlight = false }
    }

    private fun circuitOpen(retryAfterMillis: Long): NetworkTransportException = NetworkTransportException(
        NetworkFailure(
            code = "circuit_open",
            category = NetworkFailureCategory.CIRCUIT_OPEN,
            message = "服务暂时不可用",
            retryAfterMillis = retryAfterMillis,
        ),
    )

    public companion object {
        /** 默认按版本号和资源名分组，动态 ID 不会无限创建熔断状态。 */
        public fun defaultKey(request: NetworkRawRequest): String {
            val segments = request.relativePath.substringBefore('?').trim('/').split('/').filter(String::isNotEmpty)
            return segments.take(2).joinToString(separator = "/", prefix = "/").ifBlank(::defaultCircuitKey)
        }
    }
}

private data class CircuitState(
    var failureCount: Int = 0,
    var openedAtMillis: Long = -1L,
    var probeInFlight: Boolean = false,
)

private fun defaultCircuitKey(): String = "/"
