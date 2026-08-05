# 返回类型与错误

同一个 Contract 可以按业务边界选择四种返回类型。

## 返回类型

| 声明 | 行为 | 推荐场景 |
| --- | --- | --- |
| `NetworkCall<T>` | 延迟执行，可继续组合策略 | 默认选择，Repository/UseCase |
| `suspend NetworkResult<T>` | 立即执行，显式成功/失败 | 简单的一次性调用 |
| `Flow<NetworkState<T>>` | 冷流，Loading → Success/Error | UI 直接收集 |
| `suspend T` | 失败抛 `NetworkRequestException` | 已统一使用异常式调用的代码 |

```kotlin
@NetworkService
interface ResultApi {
    @GET("/call") fun call(): NetworkCall<ItemDto>
    @GET("/result") suspend fun result(): NetworkResult<ItemDto>
    @GET("/state") fun state(): Flow<NetworkState<ItemDto>>
    @GET("/data") suspend fun data(): ItemDto
}
```

## NetworkResult

```kotlin
sealed interface NetworkResult<out T> {
    data class Success<T>(
        val data: T,
        val statusCode: Int,
        val headers: Map<String, String>,
        val requestId: String,
        val durationMillis: Long,
        val attempt: Int,
        val source: NetworkResponseSource,
    ) : NetworkResult<T>

    data class Failure(val error: NetworkFailure) : NetworkResult<Nothing>
}
```

`source` 用来区分 `NETWORK`、`MEMORY_CACHE`、`STALE_CACHE` 和 `LOCAL_FALLBACK`。这比根据耗时或 Header
猜测数据来源稳定得多。

## NetworkFailure

```kotlin
data class NetworkFailure(
    val code: String,
    val category: NetworkFailureCategory,
    val message: String,
    val statusCode: Int?,
    val retryable: Boolean,
    val requestId: String,
    val attempt: Int,
    val retryAfterMillis: Long,
)
```

分类包括：

| Category | 含义 | 常见处理 |
| --- | --- | --- |
| `VALIDATION` | URL、业务数据或参数不合法 | 修复调用/提示业务错误，不重试 |
| `SERIALIZATION` | 编解码失败 | 上报协议不兼容，不盲目重试 |
| `CONNECTIVITY` / `DNS` | 离线或域名解析失败 | 等网络恢复，可使用缓存 |
| `TLS` | HTTPS/证书校验失败 | 阻断请求并上报，不能降级 HTTP |
| `TIMEOUT` | 请求超时 | 幂等请求可有限重试 |
| `CANCELLED` | 协程或页面主动取消 | 不提示为业务错误 |
| `CIRCUIT_OPEN` | 熔断器拒绝 | 尊重 `retryAfterMillis` |
| `CLIENT_THROTTLED` | 并发、队列或限流拒绝 | 降低调用频率 |
| `HTTP` | 服务端非成功状态 | 结合 `statusCode` 与业务 code |
| `RESPONSE_TOO_LARGE` | 超过响应体上限 | 改流式接口或提高明确上限 |

## 稳定分支

```kotlin
when (val result = api.detail(id).await()) {
    is NetworkResult.Success -> save(result.data)
    is NetworkResult.Failure -> when (result.error.category) {
        NetworkFailureCategory.CANCELLED -> Unit
        NetworkFailureCategory.CONNECTIVITY -> showOfflineCache()
        NetworkFailureCategory.HTTP -> showServerError(result.error.statusCode)
        else -> showGenericError(result.error.code)
    }
}
```

不要匹配平台异常类名或 `message` 文本；它们不是跨端稳定 Contract。

## 自定义错误映射

平台响应先由 `NetworkResponseAdapter` 解开统一业务外壳，再由 `NetworkErrorMapper` 把普通 HTTP 失败映射为
结构化错误。具体示例见[Converter 与响应外壳](../extensions/converter.md)。
