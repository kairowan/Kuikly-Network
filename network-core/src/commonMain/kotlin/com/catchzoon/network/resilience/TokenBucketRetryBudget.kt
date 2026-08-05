package com.catchzoon.network.resilience

import com.catchzoon.network.core.MonotonicNetworkClock
import com.catchzoon.network.core.NetworkClock
import com.catchzoon.network.core.NetworkRetryBudget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 共享令牌桶重试预算，防止服务故障时所有请求同时重试。 */
public class TokenBucketRetryBudget(
    capacity: Int = 20,
    private val refillTokens: Int = 5,
    private val refillIntervalMillis: Long = 1_000L,
    private val clock: NetworkClock = MonotonicNetworkClock,
) : NetworkRetryBudget {
    private val mutex = Mutex()
    private val capacity = capacity.toDouble()
    private var tokens = capacity.toDouble()
    private var lastRefillMillis = clock.nowMillis()

    init {
        require(capacity in 1..10_000) { "重试预算容量必须在 1..10000 之间" }
        require(refillTokens in 1..capacity) { "重试预算补充数量无效" }
        require(refillIntervalMillis in 10L..60_000L) { "重试预算补充周期无效" }
    }

    override suspend fun tryAcquire(): Boolean = mutex.withLock {
        refill()
        if (tokens < 1.0) false else true.also { tokens -= 1.0 }
    }

    private fun refill() {
        val now = clock.nowMillis()
        val elapsed = (now - lastRefillMillis).coerceAtLeast(0L)
        if (elapsed < refillIntervalMillis) return
        val intervals = elapsed / refillIntervalMillis
        tokens = (tokens + intervals * refillTokens).coerceAtMost(capacity)
        lastRefillMillis += intervals * refillIntervalMillis
    }
}
