# Engine 与协议适配

`NetworkEngine` 是平台传输的最小契约：

```kotlin
interface NetworkEngine {
    suspend fun execute(request: NetworkRawRequest): NetworkRawResponse
    fun cancelAll()
}
```

Core 已提供 Android Retrofit/OkHttp 与 iOS NSURLSession Engine。以下场景才需要自定义：

- 接入新平台。
- 公司已有统一 HTTP SDK。
- HarmonyOS 需要脱离 Kuikly Pager 的应用级请求。
- 需要真正落盘流式下载、文件流上传或特殊协议。

## 最小 Engine

```kotlin
class CompanyNetworkEngine(
    private val transport: CompanyTransport,
    private val baseUrl: String,
) : NetworkEngine {
    override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
        val url = resolveCompanyUrl(baseUrl, request.relativePath)
            ?: throw NetworkTransportException(
                NetworkFailure("invalid_url", NetworkFailureCategory.VALIDATION),
            )

        return try {
            val response = transport.execute(
                method = request.method.name,
                url = url,
                headers = request.headers,
                body = request.bodyBytes ?: request.body.encodeToByteArray(),
                timeoutSeconds = request.timeoutSeconds,
            )
            NetworkRawResponse(
                statusCode = response.code,
                body = response.bytes.decodeToString(),
                bodyBytes = response.bytes,
                headers = response.headers.mapValues { it.value.firstOrNull().orEmpty() },
                headerValues = response.headers,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CompanyTimeout) {
            throw NetworkTransportException(
                NetworkFailure(
                    code = "request_timeout",
                    category = NetworkFailureCategory.TIMEOUT,
                    retryable = true,
                ),
            )
        }
    }

    override fun cancelAll() {
        transport.cancelAll()
    }
}
```

示例中的 `CompanyTransport`/`CompanyTimeout` 是宿主类型，不属于库 API。

## Engine 必须遵守的 Contract

1. 支持 `NetworkMethod` 中声明的方法，或对不支持的方法明确失败。
2. 应用 `timeoutSeconds`、`maxResponseBytes` 和 `redirectPolicy`。
3. 尊重协程取消并取消底层平台任务。
4. 普通传输错误转成带分类的 `NetworkTransportException`。
5. 返回原始状态码、Header 和正文，不在 Engine 重复解析 DTO。
6. 调用 `progressListener` 时回调要轻量，回调异常不能破坏传输。
7. `cancelAll()` 后 Engine 是否仍可用必须与公共 `NetworkClient` Contract 一致：Android/iOS 是可继续使用。

## 创建 Client

```kotlin
val client = NetworkClient.Builder(
    CompanyNetworkEngine(transport, "https://api.example.com"),
).apply {
    commonHeaders(session::headers)
    responseCache()
}.build()
```

自定义 Engine 仍能复用所有公共 Interceptor、KSP 接口、缓存、重试和操作符。

## 直接使用原始请求

需要完全自行解释协议时：

```kotlin
val result: NetworkRawResult = client.executeRaw(
    NetworkRawRequest(
        relativePath = "/v1/raw",
        method = NetworkMethod.GET,
    ),
)
```

大多数业务仍应使用 KSP Service 或 `typedCall`，避免到处手写原始请求。

## Harmony 应用级 Engine

当前 HAR Bridge 的请求生命周期依赖 Kuikly Pager。若下载、同步或上传必须离开页面继续，应该在 ArkTS/Native
侧提供一个独立 Transport，并由 `NetworkEngine` 持有应用级句柄；不能把已销毁 Pager 的 Bridge 保存成全局单例。
