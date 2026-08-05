package com.catchzoon.network.core

/** 不携带响应正文的可观测事件，避免日志监听器意外记录敏感数据。 */
public sealed interface NetworkEvent {
    public val path: String

    public data class Started(override val path: String, val method: NetworkMethod) : NetworkEvent
    public data class Completed(
        override val path: String,
        val statusCode: Int,
        val durationMillis: Long,
        val source: NetworkResponseSource = NetworkResponseSource.NETWORK,
    ) : NetworkEvent
    public data class Cancelled(override val path: String, val durationMillis: Long) : NetworkEvent
    public data class Failed(override val path: String, val failure: NetworkFailure, val durationMillis: Long) : NetworkEvent
}

/** 埋点、性能监控和脱敏日志统一监听协议。 */
public fun interface NetworkEventListener {
    public suspend fun onEvent(event: NetworkEvent)
}
