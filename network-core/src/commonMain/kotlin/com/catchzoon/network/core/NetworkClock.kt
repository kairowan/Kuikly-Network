package com.catchzoon.network.core

import kotlin.time.TimeSource

/** 可替换时钟让缓存、熔断和监控策略能够稳定测试。 */
public fun interface NetworkClock {
    public fun nowMillis(): Long
}

/** 进程内单调时钟；不受用户修改系统时间影响。 */
public object MonotonicNetworkClock : NetworkClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}
