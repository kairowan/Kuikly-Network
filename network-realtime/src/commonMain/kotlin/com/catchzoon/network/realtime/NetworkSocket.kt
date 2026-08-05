package com.catchzoon.network.realtime

import kotlinx.coroutines.flow.Flow

/** WebSocket 发送帧。 */
public sealed interface NetworkSocketFrame {
    public data class Text(val value: String) : NetworkSocketFrame
    public data class Binary(val value: ByteArray) : NetworkSocketFrame
    public data class Closing(val code: Int, val reason: String) : NetworkSocketFrame
}

/** 单个 WebSocket 连接；平台实现负责真实传输与取消。 */
public interface NetworkSocketConnection {
    public val incoming: Flow<NetworkSocketFrame>
    public suspend fun send(frame: NetworkSocketFrame): Boolean
    public suspend fun close(code: Int = 1_000, reason: String = "")
}

/** 可替换的平台 WebSocket 传输协议。 */
public fun interface NetworkSocketTransport {
    public suspend fun connect(url: String, headers: Map<String, String>): NetworkSocketConnection
}
