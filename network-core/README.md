# network-core

`network-core` 是不依赖 Kuikly UI 的 KMP 网络内核。它统一请求模型、解析、错误、缓存、韧性、安全和
可观测性；Android 传输使用 Retrofit/OkHttp，iOS 使用 NSURLSession。Kuikly 生命周期、Inspector、Koin、
实时通信和测试替身分别位于独立模块，调用方只引入需要的能力。

完整模块矩阵、平台说明和发布流程见：

- [`../docs/network-library.md`](../docs/network-library.md)
- [`../docs/network-migration.md`](../docs/network-migration.md)
- [`../docs/network-release.md`](../docs/network-release.md)

## 声明式接口

```kotlin
@Serializable
data class UploadResult(val id: String)

@NetworkService
interface FileApi {
    /** 查询文件。 */
    @GET("/v1/files/{id}")
    @Cache(
        maxAgeSeconds = 60,
        mode = NetworkCacheMode.NETWORK_FIRST,
        staleIfErrorSeconds = 3600,
        tags = ["files"],
    )
    fun detail(
        @Path("id") id: String,
        @Header("X-Locale") locale: String,
        @RequestId requestId: String,
    ): NetworkCall<FileDto>

    /** 上传文件。 */
    @Multipart
    @POST("/v1/files")
    @InvalidateCache("files")
    fun upload(
        @Part("title") title: String,
        @Part(value = "file", fileName = "upload.bin") bytes: ByteArray,
        @IdempotencyKey idempotencyKey: String,
    ): NetworkCall<UploadResult>

    /** 下载原始内容。 */
    @Streaming
    @GET("/v1/files/{id}/content")
    fun download(@Path("id") id: String): NetworkCall<ByteArray>
}
```

支持 HTTP `GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS`；参数支持 `Path/Url/Query/QueryMap/Header/HeaderMap/Body`
以及 Form、Multipart、幂等键和请求 ID；方法策略支持超时、重试、缓存、响应上限、优先级、标签、重定向、
流式字节响应与精确缓存失效。

KSP 在编译期生成实现，不使用运行时反射。接口可以返回：

- `NetworkCall<T>`：推荐，支持不可变链式组合。
- `suspend NetworkResult<T>`：显式成功/失败。
- `Flow<NetworkState<T>>`：Loading → Success/Error 单向流。
- `suspend T`：失败抛出携带 `NetworkFailure` 的 `NetworkRequestException`。

## 创建客户端

推荐在 Application/AppDelegate 中一次性注册默认客户端和不同 Base URL 的命名客户端：

```kotlin
NetworkClients.initialize(
    defaultClient = createNetworkClient("https://api.example.com") {
        commonHeaders { commonHeadersFor(it) }
        addInterceptor(appInterceptor)
    },
    namedClients = mapOf(
        "upload" to createNetworkClient("https://upload.example.com") {
            commonHeaders { commonHeadersFor(it) }
            defaultTimeout(120)
        },
        "analytics" to createNetworkClient("https://events.example.com"),
    ),
)
```

接口可以声明默认使用的命名客户端，KSP 会同时生成无需传入 `NetworkClient` 的应用级工厂：

```kotlin
@NetworkService(client = "upload")
interface UploadApi {
    @POST("/v1/files")
    fun upload(@Body request: UploadRequest): NetworkCall<UploadResult>
}

// 任意业务类中直接创建；默认选择 Application 注册的 upload Client。
private val uploadApi = createUploadApi()

// 也可以在运行时覆盖客户端名称。
private val stagingUploadApi = createUploadApi(clientName = "staging")
```

原有 `client.createUploadApi()` 仍然保留，适合测试替身、局部 Client 和显式依赖注入。

```kotlin
val metrics = NetworkMetricsCollector()
val client = createNetworkClient(
    baseUrl = "https://api.example.com",
    tlsPolicy = NetworkTlsPolicy(
        certificatePins = mapOf("api.example.com" to setOf(currentPin, nextPin)),
    ),
) {
    defaultTimeout(20)
    defaultResponseLimit(4L * 1024L * 1024L)
    commonHeaders { commonHeadersFor(it) }
    bearerAuthentication(session::token, session::refresh)
    responseCache(store = encryptedCacheStore)
    coalesceRequests()
    priorityQueue()
    rateLimit()
    circuitBreaker()
    cookies()
    contentNegotiation("application/json")
    metrics(metrics)
    responseAdapter(companyEnvelopeAdapter)
}
```

拦截器按添加顺序执行。缓存和请求合并通常放在真实并发/限流之前，使缓存命中不占传输许可；鉴权 Header
必须在缓存键计算前加入，避免跨用户共享响应。

## 链式调用

```kotlin
val state = client.createFileApi().detail(id, locale, requestId)
    .networkFirst(maxAgeSeconds = 60, staleIfErrorSeconds = 3600)
    .retryWhen(NetworkRetryPolicy(maxAttempts = 3)) { it.retryable }
    .retryBudget(sharedRetryBudget)
    .priority(NetworkPriority.HIGH)
    .progress(::renderProgress)
    .validate(
        predicate = { it.id.isNotBlank() },
        failure = { NetworkFailure(code = "invalid_file") },
    )
    .fallbackTo(localFallbackCall)
    .asFlow()
```

还支持 `map/mapSuspend/flatMap/mapFailure/recover/recoverWith/onSuccess/onFailure/fold/enqueue`，以及有界并行、
批处理、轮询和 `StateFlow` 编排。普通网络与解析失败统一落入 `NetworkFailure`，取消仍保留协程语义。

## 序列化边界

commonMain 默认使用 Kotlin Serialization。Gson 依赖 JVM 反射，只能在 Android 源集声明：

```kotlin
@NetworkService(serialization = NetworkSerialization.GSON)
interface AndroidOnlyApi
```

公司统一响应外壳使用 `NetworkResponseAdapter` 拆解一次；自定义格式实现 `NetworkConverter` 或直接调用
`encodedCall`，无需修改平台 Engine。

## 测试和 API 稳定性

```bash
./gradlew :network-core:testDebugUnitTest
./gradlew apiCheck
./scripts/benchmark_network.sh
```

业务测试应依赖 `network-testing` 的 `ScriptedNetworkEngine`。公共 API 有意变化时运行 `apiDump`，审核并提交
Android 与 KLib 快照；不要让 CI 自动接受 ABI 变化。
