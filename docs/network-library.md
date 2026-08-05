# Kuikly Network

Kuikly Network 是面向 KMP 与 Kuikly 的模块化网络库。接口、请求参数、返回类型和策略写在同一个
Contract 中；Android 使用 Retrofit/OkHttp，iOS 使用 NSURLSession，鸿蒙通过 Kuikly 原生模块接入
NetworkKit。公共层不依赖 Activity、ViewModel 或具体业务。

## 模块

| 模块 | 职责 | 是否必选 |
| --- | --- | --- |
| `network-core` | 类型化调用、协程/Flow、序列化、缓存、韧性、安全、指标及双端引擎 | 是 |
| `network-ksp` | 编译期生成 Retrofit 风格接口实现，不使用运行时反射 | 使用注解接口时 |
| `network-kuikly` | Kuikly Pager 生命周期、任务取消和旧 JSON 调用兼容层 | Kuikly 页面 |
| `network-inspector` | 有界、脱敏、只保留内存的请求交换快照 | 调试环境 |
| `network-koin` | 可覆盖 Engine 的 Koin 模块 | 使用 Koin 时 |
| `network-realtime` | SSE 增量解析与平台无关 WebSocket SPI | 实时业务 |
| `network-testing` | 确定性脚本 Engine、离线/超时/延迟场景 | 测试 |
| `network-bom` | 对齐全部发布物版本 | 推荐 |
| `network-ohos` | 可发布的 OHPM 源码包，提供 NetworkKit/Kuikly 原生桥接 | 鸿蒙宿主 |

## 能力矩阵

| 等级 | 已完成能力 |
| --- | --- |
| P0 | GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS、Path/Query/Header、JSON/Form/Multipart/ByteArray、Kotlin Serialization、Android Gson、自定义 Converter、统一成功/失败、协程取消、冷 Flow 单向状态、链式操作符 |
| P1 | 有界缓存、Cache First/Network First/Cache Only/SWR/过期兜底、标签精确失效、请求合并、并发背压、优先级队列、限流、重试预算、三态熔断、Cookie、内容协商、受控重定向 |
| P2 | Android/iOS 传输、上传/下载进度、动态 HTTPS URL、响应大小边界、TLS/证书 Pin 轮换、认证单飞刷新、脱敏 Inspector、滚动指标、轮询/批量/并行编排、SSE/WebSocket SPI |
| P3 | Kuikly 生命周期适配、Koin、测试工件、Maven BOM/POM/可选签名、KMP ABI 快照、CI 门禁、基准脚本、鸿蒙 NetworkKit 桥接及后续平台 Engine 扩展点 |

## 最小接入

```kotlin
commonMain.dependencies {
    implementation(platform("com.catchzoon.network:network-bom:0.1.1"))
    implementation("com.catchzoon.network:network-core")
}
```

启用声明式接口的模块同时添加 `network-ksp` 到各编译目标。仓库内完整配置可参考
`network-core/build.gradle.kts`。

```kotlin
@Serializable
data class CardDto(val id: String, val title: String)

@NetworkService
interface CardApi {
    /** 获取卡片详情。 */
    @GET("/v1/cards/{id}")
    @Timeout(15)
    @Cache(maxAgeSeconds = 60, tags = ["cards"])
    fun detail(
        @Path("id") id: String,
        @RequestId requestId: String,
    ): NetworkCall<CardDto>
}

val api = client.createCardApi()
val state = api.detail(cardId, requestId)
    .networkFirst(maxAgeSeconds = 60, staleIfErrorSeconds = 3600)
    .retryWhen(NetworkRetryPolicy(maxAttempts = 3)) { it.retryable }
    .timeout(15)
    .asFlow()
```

生成器也支持 `suspend NetworkResult<T>`、`Flow<NetworkState<T>>` 和 `suspend T`。推荐让领域层保留
`NetworkCall<T>`，到 UI 边界再决定结果式、异常式还是状态流。

## Kuikly 页面

宿主应优先在 Application/AppDelegate 创建应用级客户端。不同服务使用命名 Client，不需要把 Base URL
放进页面参数，也不需要在每个页面重复创建 Client：

```kotlin
NetworkClients.initialize(
    defaultClient = createNetworkClient(BuildConfig.API_BASE_URL) {
        commonHeaders(session::headers)
    },
    namedClients = mapOf(
        "upload" to createNetworkClient(BuildConfig.UPLOAD_BASE_URL),
        "analytics" to createNetworkClient(BuildConfig.ANALYTICS_BASE_URL),
    ),
)
```

`@NetworkService(client = "upload")` 生成的 `createUploadApi()` 会自动从注册表选择对应 Client；未声明
`client` 时使用 `default`。业务仍可使用 `NetworkClients.client("upload")` 直接取得 Client。

```kotlin
private val client by lazy {
    createKuiklyNetworkClient(pager, BuildConfig.API_BASE_URL) {
        defaultTimeout(20)
        responseCache()
        coalesceRequests()
        limitConcurrency(maxConcurrentRequests = 8)
    }
}

pager.launchRequest(api.detail(id, requestId), ::render, ::renderError)

override fun pageWillDestroy() {
    pager.closeNetworkScope()
    super.pageWillDestroy()
}
```

`network-kuikly` 负责把页面任务与底层客户端同时取消。非 Kuikly 调用方直接使用
`NetworkClient.Builder`，两者不会相互依赖。

应用级 Client 不应注册到页面 `networkScope`；页面只取消自己的协程，Repository/Application Scope 发起的
请求可以继续执行。HarmonyOS 内置 Bridge 当前仍需要活动的 Kuikly Pager，若要在页面销毁后继续执行，宿主
需要提供应用级 `NetworkEngine`，不能复用已经销毁页面的 Bridge。

## 平台边界

| 平台 | 传输实现 | 扩展方式 |
| --- | --- | --- |
| Android | Retrofit 3 + OkHttp | 注入定制 `OkHttpClient` 或实现 `NetworkEngine` |
| iOS | NSURLSession Delegate | 使用公共 `NetworkEngine` 契约，不暴露 Foundation 给业务 |
| HarmonyOS | ArkTS NetworkKit + `KRNetworkModule` | 引用 `@catchzoon/network-ohos`；公共 Contract 与 KSP 接口不改 |
| 新平台 | 无预设依赖 | 实现 `NetworkEngine.execute/cancelAll`，或提供 Kuikly 原生模块 |

Gson 依赖 JVM 反射，只允许 Android 源集使用；跨端 DTO 默认使用 Kotlin Serialization。鸿蒙采用
Kuikly KBA 工具链，并使用 `2.0.21-coroutines-KBA-001` 与 `1.7.1-KBA-003` 的 OHOS KLib；
ArkTS 适配器单独作为 OHPM 包交付，不污染标准 Maven KMP 元数据。

## 安全默认值

- 动态绝对 URL 只接受 HTTPS，禁止用户信息、片段和控制字符。
- 重定向默认同源；跨域必须显式允许，且 Android/iOS 会移除鉴权与 Cookie 头。
- POST/PATCH 等非幂等请求只有提供 Idempotency-Key 或显式允许时才重试。
- Inspector 不保存正文，只记录字节数，并自动脱敏凭据、Cookie 和 token 查询参数。
- 默认限制响应体、并发队列、缓存项、重试次数和熔断探测，避免无限占用内存。
- Pin、密钥和仓库凭据只从部署配置注入，不写进源码。

## 验证

```bash
./gradlew :network-core:testDebugUnitTest \
  :network-inspector:testDebugUnitTest \
  :network-realtime:testDebugUnitTest \
  :network-testing:testDebugUnitTest

./gradlew apiCheck
./scripts/benchmark_network.sh
./scripts/build_harmony.sh
./scripts/build_harmony.sh har
```

鸿蒙构建要求已安装 DevEco/OpenHarmony SDK，并设置 `OHOS_SDK_HOME` 或 `DEVECO_STUDIO_HOME`。
构建脚本会使用 Kuikly 官方 OHOS settings，并把 `shared`、`network-core`、`network-kuikly` 和
`network-ksp` 纳入同一个 KBA 编译图；缺少 SDK 时会在编译前给出明确提示。第二条命令会生成
携带 `libshared.so` 的 release HAR，使用方式见 [HarmonyOS / HAR](platforms/harmony.md)。
