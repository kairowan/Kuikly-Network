package com.catchzoon.network.core

/** 控制一段时间内可消耗的自动重试次数，避免故障风暴。 */
public fun interface NetworkRetryBudget {
    public suspend fun tryAcquire(): Boolean
}

/**
 * 有上限的指数退避策略。
 *
 * POST/PATCH 默认只有携带 Idempotency-Key 时才允许自动重试，防止重复写入。
 */
public data class NetworkRetryPolicy(
    val maxAttempts: Int = 1,
    val initialDelayMillis: Long = 300L,
    val maxDelayMillis: Long = 5_000L,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.2,
    val retryUnsafeMethods: Boolean = false,
) {
    init {
        require(maxAttempts in 1..MAX_ATTEMPTS) { "maxAttempts 必须在 1..$MAX_ATTEMPTS 之间" }
        require(initialDelayMillis >= 0L && maxDelayMillis >= initialDelayMillis) { "重试延迟配置无效" }
        require(multiplier >= 1.0) { "multiplier 不能小于 1" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio 必须在 0..1 之间" }
    }

    public companion object {
        /** 不自动重试。 */
        public fun none(): NetworkRetryPolicy = NetworkRetryPolicy(maxAttempts = 1)
    }
}

private const val MAX_ATTEMPTS = 6
