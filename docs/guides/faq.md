# 常见问题

## 为什么生成的 `createXxxApi()` 找不到？

确认声明接口的模块应用了 KSP，并在对应 source set 加入 `network-ksp`：

```kotlin
plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    add("kspCommonMainMetadata", project(":network-ksp"))
}
```

然后执行一次编译。如果接口声明不合法，KSP 会在编译日志中指出具体注解或参数。

## 一定要把 `NetworkClient` 传到每个类吗？

不需要。应用启动时通过 `NetworkClients.initialize(...)` 注册，业务代码可以直接调用生成的无参工厂。测试、SDK 多实例或需要严格隔离时，仍建议使用 `createXxxApi(client)` 显式传入。

## 如何配置多个 Base URL？

使用命名客户端：

```kotlin
NetworkClients.initialize(
    defaultClient = defaultClient,
    namedClients = mapOf(
        "content" to contentClient,
        "upload" to uploadClient,
    ),
)

@NetworkService(client = "upload")
interface UploadApi
```

单个请求确实需要完整动态地址时可使用 `@Url`，但固定服务边界优先使用命名客户端，便于统一鉴权、缓存和监控。

## 离开页面后请求为什么取消？

请求跟随执行它的协程作用域。页面作用域被取消，请求也会取消；需要跨页继续的上传或同步任务，应在应用级作用域执行，并使用应用级客户端。详见 [Kuikly 页面接入](../platforms/kuikly.md)。

## 能添加自己的拦截器吗？

可以。实现 `NetworkInterceptor` 并在 `NetworkClient.Builder` 中注册即可。它能修改请求、读取响应、统一鉴权和记录日志。不要在拦截器中吞掉协程取消异常。

## POST 可以自动重试吗？

只有业务确认幂等时才应该重试。为请求提供 `@IdempotencyKey` 或 `.idempotencyKey(...)`，服务端也必须按该键去重。支付、创建订单等请求不要仅因网络失败就盲目重放。

## 为什么配置了缓存却没有命中？

检查客户端是否安装了 `responseCache`、响应是否允许缓存、请求是否被 `noCache()` 覆盖，以及缓存键是否因 Header 或 Query 不同而变化。调试时可结合 Inspector 查看实际请求和命中状态。

## iOS 可以使用 Gson 吗？

Gson 依赖 JVM 反射，不适合 Kotlin/Native。公共代码使用 `kotlinx.serialization`，或者提供实现 `NetworkConverter` 的跨平台转换器。

## 为什么 iOS 编译找不到 Pods 或 Framework？

先运行 `pod install`，然后打开 `iosApp.xcworkspace`，不要打开 `.xcodeproj`。共享 Framework 改动后可重新执行 `:shared:generateDummyFramework`。

## HarmonyOS 请求离开页面能继续吗？

当前内置 HarmonyOS Bridge 的引擎依赖活动 Kuikly `Pager`。若要跨页继续，需要宿主实现应用级 HarmonyOS `NetworkEngine`。HAR/HAP 的构建和安装不受此限制。

## `._` AppleDouble 文件是什么？

这是 macOS 在 ExFAT、网络盘等不支持原生扩展属性的文件系统上保存 Finder 元数据时生成的伴随文件，不是项目源码。

仓库已经通过 `.gitignore` 忽略 `._*`。在 Android Studio / IntelliJ IDEA 中还可以打开：

```text
Settings / Preferences
→ Editor
→ File Types
→ Ignore files and folders
```

追加 `._*;` 后它们不会显示在 Project 视图中。已有文件可以在确认目录范围后使用 macOS 的 `dot_clean <目录>` 合并或清理；不要对不明确的路径执行批量删除。

## 自定义能力从哪里开始？

先看[自定义扩展总览](../extensions/index.md)。常见选择是：

- 修改请求或统一鉴权：`NetworkInterceptor`；
- 接入新平台 HTTP 栈：`NetworkEngine`；
- 使用自定义序列化：`NetworkConverter`；
- 统一业务错误：`NetworkErrorMapper`；
- 自定义磁盘缓存或 Cookie：`NetworkCacheStore` / `NetworkCookieStore`；
- 对接日志与性能平台：`NetworkEventListener`。
