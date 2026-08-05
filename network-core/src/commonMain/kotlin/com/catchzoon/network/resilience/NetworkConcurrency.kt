package com.catchzoon.network.resilience

import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkTransportException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** 客户端并发背压策略。 */
public data class NetworkConcurrencyPolicy(
    val maxConcurrentRequests: Int = 8,
    val maxQueueWaitMillis: Long = 1_000L,
) {
    init {
        require(maxConcurrentRequests in 1..64) { "maxConcurrentRequests 必须在 1..64 之间" }
        require(maxQueueWaitMillis in 0L..60_000L) { "maxQueueWaitMillis 必须在 0..60000 之间" }
    }
}

/** 限制真实请求并发数；等待超限会返回结构化失败，而不是无限堆积协程。 */
public class NetworkConcurrencyInterceptor(
    private val policy: NetworkConcurrencyPolicy,
) : NetworkInterceptor {
    private val permits = Semaphore(policy.maxConcurrentRequests)

    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        val acquired = if (policy.maxQueueWaitMillis == 0L) {
            permits.tryAcquire()
        } else {
            withTimeoutOrNull(policy.maxQueueWaitMillis.milliseconds) {
                permits.acquire()
                true
            } ?: false
        }
        if (!acquired) throw NetworkTransportException(
            NetworkFailure(
                code = "client_throttled",
                category = NetworkFailureCategory.CLIENT_THROTTLED,
                message = "客户端请求繁忙",
                retryAfterMillis = policy.maxQueueWaitMillis,
            ),
        )
        return try {
            chain.proceed()
        } finally {
            permits.release()
        }
    }
}
