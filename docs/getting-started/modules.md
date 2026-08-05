# 模块与能力

Kuikly Network 按职责拆分模块。只引入真正使用的能力，能够减少依赖、编译时间和平台耦合。

## 模块矩阵

| 模块 | 必选 | 公开能力 | 适用场景 |
| --- | --- | --- | --- |
| `network-core` | 是 | Client、KSP 注解、Call/Result/Flow、缓存与韧性、Android/iOS Engine | 所有调用方 |
| `network-ksp` | 否 | `@NetworkService` 实现生成器 | 使用声明式接口 |
| `network-kuikly` | 否 | Pager 网络作用域、Bridge、JSON 兼容 Facade | Kuikly 页面 |
| `network-inspector` | 否 | 脱敏请求快照 `StateFlow` | Debug/内部诊断 |
| `network-koin` | 否 | `networkKoinModule` | 使用 Koin 的项目 |
| `network-realtime` | 否 | SSE 解析、WebSocket SPI | 推送、聊天、实时状态 |
| `network-testing` | 否 | `ScriptedNetworkEngine`、Fixture、离线/超时场景 | 单元测试 |
| `network-bom` | 推荐 | Maven 版本对齐 | Maven/OHPM 发布消费 |
| `network-ohos` | 鸿蒙必选 | NetworkKit 与 Kuikly Bridge HAR | HarmonyOS 宿主 |

## 架构边界

```text
业务 Contract / DTO
         │
         ├── network-ksp（只在编译期）
         ▼
network-core（平台无关请求语义）
         │
         ├── Android: Retrofit / OkHttp
         ├── iOS: NSURLSession
         ├── Kuikly: network-kuikly
         └── HarmonyOS: network-ohos HAR
```

- `network-core` 不依赖 Activity、ViewModel、UIView 或 ArkTS 页面。
- KSP 只生成对 `NetworkClient` 的调用，不自己执行网络请求。
- Inspector、Koin、Realtime 和 Testing 不会被核心模块反向依赖。
- 平台 Engine 只负责传输；缓存、重试、鉴权等策略由公共层实现。

## 怎么选

=== "普通 KMP"

    `network-core` + `network-ksp`。如果不用注解，也可以只使用 `network-core` 的类型化调用或 Route DSL。

=== "Kuikly 页面"

    `network-core` + `network-ksp` + `network-kuikly`。页面作用域请求由 Pager 生命周期管理。

=== "大型应用"

    在基础模块上按需增加 `network-inspector`、`network-koin`、`network-realtime` 和 `network-testing`。

=== "HarmonyOS"

    Kotlin 公共代码仍使用 Core/KSP；ArkTS 宿主通过 `network-ohos.har` 注册 Bridge。

## 当前平台边界

| 能力 | Android | iOS | Harmony Bridge |
| --- | :---: | :---: | :---: |
| JSON 与全部 HTTP 方法 | ✅ | ✅ | ✅ |
| 协程取消 | ✅ | ✅ | ✅（随 Pager） |
| 上传/下载进度 | ✅ | ✅ | 当前 Bridge 不支持 |
| 动态 HTTPS URL | ✅ | ✅ | ✅ |
| 证书 Pin | ✅ | ✅ | 需要专用 Harmony Engine |
| 二进制/流式响应 | ✅ | ✅ | 需要专用 Harmony Engine |
| 应用级离页继续请求 | ✅ | ✅ | 需要应用级 Harmony Engine |

Harmony 限制会明确失败，不会静默退化为不安全或语义不同的行为。
