<div align="center">

# Kuikly Network

面向 Kotlin Multiplatform 与 Kuikly 的类型安全、可组合网络库。

[![Build](https://github.com/kairowan/Kuikly-Network/actions/workflows/network-library.yml/badge.svg)](https://github.com/kairowan/Kuikly-Network/actions/workflows/network-library.yml)
[![Docs](https://github.com/kairowan/Kuikly-Network/actions/workflows/docs.yml/badge.svg)](https://kairowan.github.io/Kuikly-Network/)
[![JitPack](https://jitpack.io/v/kairowan/Kuikly-Network.svg)](https://jitpack.io/#kairowan/Kuikly-Network)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-Apache--2.0-2f855a.svg)](NETWORK-LICENSE)

[完整文档](https://kairowan.github.io/Kuikly-Network/) ·
[快速开始](https://kairowan.github.io/Kuikly-Network/getting-started/quick-start/) ·
[注解手册](https://kairowan.github.io/Kuikly-Network/api/annotations/) ·
[操作符](https://kairowan.github.io/Kuikly-Network/operators/call/) ·
[扩展能力](https://kairowan.github.io/Kuikly-Network/extensions/)

</div>

Kuikly Network 把请求 Contract、平台传输、缓存与韧性策略拆成独立层。业务使用同一套接口运行在
Android、iOS 和 Kuikly；Android 由 Retrofit/OkHttp 传输，iOS 使用原生 `NSURLSession`，HarmonyOS
通过可发布的 OHPM HAR 接入 NetworkKit。

## 为什么使用它

- **类型安全**：KSP 根据 `@NetworkService` 接口生成实现，没有运行时反射。
- **应用级客户端**：在 Application/AppDelegate 初始化一次，业务类直接创建 API，不逐层传 Client。
- **多个 Base URL**：默认、上传、埋点等服务分别注册命名 Client，每个 Client 有独立配置。
- **可组合调用**：`map`、`flatMap`、`retryWhen`、`cacheFirst`、`fallbackTo`、`asFlow` 等操作不会修改原调用。
- **跨端一致错误**：网络、HTTP、超时、TLS、取消与解析错误统一为 `NetworkFailure`。
- **可靠性内建**：缓存、请求合并、并发背压、优先级、限流、重试预算、熔断和离线快速失败按需启用。
- **开放扩展**：Interceptor、Engine、Converter、Response Adapter、Cache Store、Call Adapter 等都有公开契约。
- **可验证**：提供脱敏 Inspector、滚动指标和确定性的 `ScriptedNetworkEngine`。

## 模块

| 模块 | 用途 |
| --- | --- |
| `network-core` | 请求、KSP 注解、序列化、缓存、韧性、操作符及 Android/iOS Engine |
| `network-ksp` | 编译期生成声明式接口实现 |
| `network-kuikly` | Kuikly Pager 生命周期和兼容 JSON Facade |
| `network-inspector` | 有界、脱敏、仅内存的请求检查器 |
| `network-koin` | 可覆盖 Engine 的 Koin 模块 |
| `network-realtime` | SSE 解析与平台无关 WebSocket SPI |
| `network-testing` | 脚本化响应、失败、延迟和请求记录 |
| `network-bom` | Maven 模块版本对齐 |
| `network-ohos` | HarmonyOS NetworkKit/Kuikly Bridge HAR |

## 安装

稳定版本通过 JitPack 发布。先在消费工程中加入仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.kairowan.Kuikly-Network") }
        }
    }
}
```

按需添加模块：

```kotlin
val networkVersion = "v0.1.0"

commonMain.dependencies {
    implementation(platform("com.github.kairowan.Kuikly-Network:network-bom:$networkVersion"))
    implementation("com.github.kairowan.Kuikly-Network:network-core:$networkVersion")
}

dependencies {
    add("kspAndroid", "com.github.kairowan.Kuikly-Network:network-ksp:$networkVersion")
}
```

JitPack 构建 Linux/Android 产物；GitHub Release 的 Maven 压缩包额外包含 macOS 构建的 iOS KLib。
HarmonyOS 使用同一 Release 附带的 `network-ohos-0.1.0.har`。

## 三步发起请求

### 1. 在应用入口初始化

```kotlin
NetworkClients.initialize(
    defaultClient = createNetworkClient("https://api.example.com") {
        commonHeaders { mapOf("X-App-Version" to appVersion) }
        addInterceptor(appInterceptor)
    },
    namedClients = mapOf(
        "upload" to createNetworkClient("https://upload.example.com") {
            defaultTimeout(120)
        },
    ),
)
```

### 2. 声明接口

```kotlin
@Serializable
data class UserDto(val id: String, val name: String)

@NetworkService
interface UserApi {
    @GET("/v1/users/{id}")
    @Cache(maxAgeSeconds = 60, tags = ["users"])
    fun detail(@Path("id") id: String): NetworkCall<UserDto>
}
```

### 3. 在任意业务类调用

```kotlin
private val api = createUserApi()

suspend fun load(id: String): NetworkResult<UserDto> = api.detail(id)
    .networkFirst(maxAgeSeconds = 60, staleIfErrorSeconds = 3_600)
    .retryWhen(NetworkRetryPolicy(maxAttempts = 3)) { it.retryable }
    .await()
```

`@NetworkService(client = "upload")` 会让生成的无参工厂自动选择 `upload` Client；原有
`client.createUserApi()` 仍然保留，适合测试替身和显式依赖注入。

## 平台状态

| 平台 | 状态 | 说明 |
| --- | --- | --- |
| Android | ✅ | Retrofit 3 + OkHttp，支持完整请求、进度、重定向和证书 Pin |
| iOS | ✅ | 原生 NSURLSession，支持协程取消、进度、重定向和证书 Pin |
| HarmonyOS | ✅ Bridge | HAR 可编译接入；当前 Bridge 依赖活动 Kuikly Pager，二进制/流式需专用 Engine |

## 本地验证

```bash
./gradlew apiCheck \
  :network-core:testDebugUnitTest \
  :network-inspector:testDebugUnitTest \
  :network-realtime:testDebugUnitTest \
  :network-testing:testDebugUnitTest \
  :androidApp:assembleDebug

./scripts/check_network_architecture.sh
DEVECO_STUDIO_HOME=/Applications/DevEco-Studio.app ./scripts/build_harmony.sh har
```

更多内容请阅读[在线文档](https://kairowan.github.io/Kuikly-Network/)，包括全部注解、返回类型、操作符、
缓存策略、拦截器顺序、扩展接口、三端运行和测试示例。

## License

Apache License 2.0，详见 [NETWORK-LICENSE](NETWORK-LICENSE)。
