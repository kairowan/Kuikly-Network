# 全部扩展点

Kuikly Network 的扩展原则是：替换最窄的一层，不 fork 整个请求栈。

## 扩展矩阵

| 契约 | 解决什么 | 何时实现 | 入口 |
| --- | --- | --- | --- |
| `NetworkInterceptor` | Header、签名、鉴权、缓存、日志、路由 | 请求前后横切逻辑 | `Builder.addInterceptor` |
| `NetworkEngine` | 真正的平台传输 | 新平台、已有 HTTP SDK、应用级 Harmony | `NetworkClient.Builder(engine)` |
| `NetworkConverter` | DTO 编解码 | 自定义 JSON/二进制文本协议 | `Builder.converter` |
| `NetworkResponseAdapter` | 解开统一业务外壳 | `{code,data,message}` 协议 | `Builder.responseAdapter` |
| `NetworkErrorMapper` | HTTP/业务失败归一化 | 公司错误码体系 | `Builder.errorMapper` |
| `NetworkCallAdapter` | 转换上层异步类型 | RxJava、自定义 Task/Promise | `call.adapt(adapter)` |
| `NetworkCacheStore` | 响应缓存介质 | 数据库、加密磁盘、共享缓存 | `responseCache(store)` |
| `NetworkCookieStore` | Cookie 持久化 | Keychain/Keystore/数据库 | `cookies(store)` |
| `NetworkConnectivityProvider` | 平台网络状态 | 离线快速失败、UI 网络状态 | `connectivity(provider)` |
| `NetworkEventListener` | 指标与遥测 | 生产监控、性能采样 | `Builder.addEventListener` |
| `NetworkProgressListener` | 传输进度 | 上传/下载 UI | `call.progress(listener)` |
| `NetworkRetryBudget` | 全局重试配额 | 大规模请求防故障风暴 | `Builder.retryBudget` |
| `NetworkSocketTransport` | WebSocket 平台实现 | 聊天、推送、实时协议 | 业务 Repository 注入 |
| `NetworkEncoder/Decoder` | 单 Endpoint 编解码 | 不想替换全局 Converter | `NetworkEndpoint` |

## 选择顺序

```text
只修改请求/响应？        → Interceptor
服务端有统一 JSON 外壳？  → ResponseAdapter
JSON 规则不同？          → Converter
平台没有传输实现？        → Engine
要接 Rx/自定义 Task？     → CallAdapter
要换存储？               → CacheStore / CookieStore
```

## 宿主 Builder 扩展

常用组合可以直接封装成 Builder 扩展，不需要创建新抽象层：

```kotlin
fun NetworkClient.Builder.companyDefaults(
    session: Session,
    metrics: NetworkMetricsCollector,
): NetworkClient.Builder = apply {
    commonHeaders { session.headers() }
    bearerAuthentication(session::token, session::refresh)
    contentNegotiation("application/json")
    coalesceRequests()
    priorityQueue()
    circuitBreaker()
    metrics(metrics)
}
```

```kotlin
val client = createNetworkClient(baseUrl) {
    companyDefaults(session, metrics)
    addInterceptor(productSpecificSigner)
}
```

这种扩展只是组合已有能力，容易测试，也不会让业务继承某个“万能 BaseClient”。

## 自定义优先于修改 Core 的情况

- 公司签名和 Header 规范属于宿主，不应该进开源 Core。
- Keychain、Keystore、SQLDelight 等存储实现应放在平台/基础设施模块。
- 业务错误 code 的含义由服务端协议决定，使用 Adapter/Mapper。
- 新平台的 SDK 生命周期和线程模型由 Engine 隔离。

只有多个无关宿主都需要且语义稳定的能力，才值得加入 Core。
