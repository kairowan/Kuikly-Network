# SSE 与 WebSocket

`network-realtime` 提供协议层，不强制核心模块依赖某个跨平台 Socket 库。

## SSE 增量解析

Transport 只需提供任意分片边界的文本 Flow：

```kotlin
val events: Flow<ServerSentEvent> = parseServerSentEvents(textChunks)

events.collect { event ->
    when (event.event) {
        "message" -> renderMessage(event.data)
        "done" -> finish()
    }
}
```

Parser 支持：

- `data:` 多行合并。
- `event:`、`id:`、`retry:`。
- `\r\n` 标准化。
- 网络分片落在任意字符块边界。
- 以 `:` 开头的注释行。

`ServerSentEvent`：

```kotlin
data class ServerSentEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
    val retryMillis: Long? = null,
)
```

连接建立、重连、Last-Event-ID 和鉴权仍由宿主 Transport 负责；Parser 只解释协议文本。

## WebSocket SPI

```kotlin
interface NetworkSocketConnection {
    val incoming: Flow<NetworkSocketFrame>
    suspend fun send(frame: NetworkSocketFrame): Boolean
    suspend fun close(code: Int = 1_000, reason: String = "")
}

fun interface NetworkSocketTransport {
    suspend fun connect(
        url: String,
        headers: Map<String, String>,
    ): NetworkSocketConnection
}
```

Frame 包括 `Text`、`Binary` 和 `Closing`。平台实现可以选择 OkHttp WebSocket、NSURLSession WebSocketTask、
Harmony NetworkKit 或已有业务 Socket SDK，而公共业务只依赖 SPI。

```kotlin
class ChatRepository(
    private val transport: NetworkSocketTransport,
) {
    suspend fun connect(token: String): NetworkSocketConnection = transport.connect(
        url = "wss://chat.example.com/v1/socket",
        headers = mapOf("Authorization" to "Bearer $token"),
    )
}
```

## 连接生命周期

- Socket 应由 Repository/Application Scope 持有，不要无条件绑到单个页面。
- 页面只收集 `incoming`，取消页面收集不一定意味着关闭共享连接。
- 明确提供 `close()`，并让平台 Transport 释放底层任务。
- 重连策略要有上限、退避和网络状态判断，不能 while(true) 紧循环。

## 选择协议

| 需求 | 推荐 |
| --- | --- |
| 服务端到客户端单向文本事件 | SSE |
| 双向消息/二进制帧 | WebSocket |
| 短任务状态、服务端无推送能力 | `pollNetwork` |
| 普通一次性请求 | `NetworkCall` |

Realtime 模块只定义必要协议，业务可以在它之上实现心跳、确认、重连和消息持久化，而不污染普通 HTTP Client。
