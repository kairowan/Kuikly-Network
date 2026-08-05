package com.catchzoon.network.resilience

import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkPriority
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 有界优先级队列配置。 */
public data class NetworkPriorityPolicy(
    val maxConcurrentRequests: Int = 8,
    val maxQueuedRequests: Int = 128,
    val maxQueueWaitMillis: Long = 5_000L,
) {
    init {
        require(maxConcurrentRequests in 1..64) { "最大并发数无效" }
        require(maxQueuedRequests in 0..10_000) { "最大排队数无效" }
        require(maxQueueWaitMillis in 1L..60_000L) { "最大排队时间无效" }
    }
}

/** 用户可见请求优先出队，同优先级保持 FIFO；队列有界且支持协程取消。 */
public class NetworkPriorityInterceptor(
    private val policy: NetworkPriorityPolicy = NetworkPriorityPolicy(),
) : NetworkInterceptor {
    private val mutex = Mutex()
    private val waiters = mutableListOf<PriorityWaiter>()
    private var inFlight = 0
    private var sequence = 0L

    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        acquire(chain.request.priority)
        return try {
            chain.proceed()
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: NetworkPriority) {
        val waiter = mutex.withLock {
            if (inFlight < policy.maxConcurrentRequests) {
                inFlight++
                return
            }
            if (waiters.size >= policy.maxQueuedRequests) throw queueFull()
            PriorityWaiter(priority, sequence++, CompletableDeferred()).also(waiters::add)
        }
        val acquired = try {
            withTimeoutOrNull(policy.maxQueueWaitMillis) { waiter.signal.await(); true } ?: false
        } catch (cancelled: CancellationException) {
            mutex.withLock { waiters.remove(waiter) }
            throw cancelled
        }
        if (!acquired) {
            val removed = mutex.withLock { waiters.remove(waiter) }
            if (removed) throw queueFull()
            // 超时与出队同时发生时，出队方已经把并发名额交给当前请求。
            waiter.signal.await()
        }
    }

    private suspend fun release() {
        val next = mutex.withLock {
            val selected = waiters.maxWithOrNull(compareBy<PriorityWaiter>({ it.priority.ordinal }, { -it.sequence }))
            if (selected == null) inFlight = (inFlight - 1).coerceAtLeast(0) else waiters.remove(selected)
            selected
        }
        next?.signal?.complete(Unit)
    }

    private fun queueFull(): NetworkTransportException = NetworkTransportException(
        NetworkFailure(
            code = "client_queue_full",
            category = NetworkFailureCategory.CLIENT_THROTTLED,
            message = "客户端请求队列已满",
            retryAfterMillis = policy.maxQueueWaitMillis,
        ),
    )
}

private data class PriorityWaiter(
    val priority: NetworkPriority,
    val sequence: Long,
    val signal: CompletableDeferred<Unit>,
)
