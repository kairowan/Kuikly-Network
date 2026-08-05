package com.catchzoon.network.resilience

import com.catchzoon.network.core.MonotonicNetworkClock
import com.catchzoon.network.core.NetworkClock
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkTransportException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 每个接口组的客户端令牌桶限流配置。 */
public data class NetworkRateLimitPolicy(
    val capacity: Int = 20,
    val refillTokens: Int = 10,
    val refillIntervalMillis: Long = 1_000L,
    val maxTrackedBuckets: Int = 128,
) {
    init {
        require(capacity in 1..10_000) { "限流容量无效" }
        require(refillTokens in 1..capacity) { "限流补充数量无效" }
        require(refillIntervalMillis in 10L..60_000L) { "限流补充周期无效" }
        require(maxTrackedBuckets in 1..1_024) { "限流分组数量无效" }
    }
}

/** 按请求标签或接口路径限制瞬时流量。 */
public class NetworkRateLimitInterceptor(
    private val policy: NetworkRateLimitPolicy = NetworkRateLimitPolicy(),
    private val clock: NetworkClock = MonotonicNetworkClock,
    private val keySelector: (NetworkRawRequest) -> String = { request ->
        request.tags.firstOrNull { it.startsWith("rate:") }
            ?: request.relativePath.substringBefore('?').trim('/').substringBefore('/')
    },
) : NetworkInterceptor {
    private val mutex = Mutex()
    private val buckets = linkedMapOf<String, RateBucket>()

    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        val retryAfter = acquire(keySelector(chain.request).ifBlank { "/" })
        if (retryAfter > 0L) throw throttled(retryAfter)
        return chain.proceed()
    }

    private suspend fun acquire(key: String): Long = mutex.withLock {
        val now = clock.nowMillis()
        val bucket = buckets[key] ?: RateBucket(policy.capacity.toDouble(), now).also {
            if (buckets.size >= policy.maxTrackedBuckets) buckets.remove(buckets.keys.first())
            buckets[key] = it
        }
        val elapsed = (now - bucket.lastRefillMillis).coerceAtLeast(0L)
        val intervals = elapsed / policy.refillIntervalMillis
        if (intervals > 0L) {
            bucket.tokens = (bucket.tokens + intervals * policy.refillTokens).coerceAtMost(policy.capacity.toDouble())
            bucket.lastRefillMillis += intervals * policy.refillIntervalMillis
        }
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            0L
        } else {
            (policy.refillIntervalMillis - (now - bucket.lastRefillMillis)).coerceAtLeast(1L)
        }
    }

    private fun throttled(retryAfterMillis: Long): NetworkTransportException = NetworkTransportException(
        NetworkFailure(
            code = "client_rate_limited",
            category = NetworkFailureCategory.CLIENT_THROTTLED,
            message = "请求过于频繁",
            retryable = true,
            retryAfterMillis = retryAfterMillis,
        ),
    )
}

private data class RateBucket(var tokens: Double, var lastRefillMillis: Long)
