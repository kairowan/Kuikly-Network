---
hide:
  - navigation
  - toc
---

<section class="kn-hero">
  <h1>Kuikly Network</h1>
  <p>一套 Contract，连接 Android、iOS、Kuikly 与 HarmonyOS。类型安全、可组合、可观测，也允许宿主替换每一层。</p>
  <div class="kn-actions">
    <a href="getting-started/quick-start/" class="md-button md-button--primary">五分钟开始</a>
    <a href="api/annotations/" class="md-button">查看全部注解</a>
    <a href="https://github.com/kairowan/Kuikly-Network" class="md-button">GitHub</a>
  </div>
</section>

<div class="kn-grid">
  <a class="kn-card" href="getting-started/application-init/">
    <h3>应用级单例</h3>
    <p>启动时初始化一次，Repository、UseCase 或页面直接创建类型化 API。</p>
  </a>
  <a class="kn-card" href="api/annotations/">
    <h3>声明式 Contract</h3>
    <p>HTTP 方法、参数、缓存与重试策略由 KSP 在编译期生成。</p>
  </a>
  <a class="kn-card" href="operators/call/">
    <h3>不可变操作符</h3>
    <p>转换、组合、重试、缓存、降级和 Flow 输出可以按场景自由拼接。</p>
  </a>
  <a class="kn-card" href="extensions/">
    <h3>开放扩展</h3>
    <p>替换 Interceptor、Engine、Converter、Store、Adapter 或实时传输。</p>
  </a>
</div>

## 一眼看懂调用链

```text
@NetworkService 接口
        │ KSP 生成
        ▼
NetworkCall<T> ── 操作符/单次策略
        │
        ▼
NetworkClient ── Interceptor 链 ── Cache / Auth / Retry / Metrics
        │
        ▼
NetworkEngine ── Android OkHttp / iOS NSURLSession / Harmony Bridge / 测试替身
```

业务层只依赖稳定的 `NetworkCall`、`NetworkResult` 和 `NetworkFailure`。平台差异留在 Engine，横切能力留在
Interceptor，服务端统一外壳留在 Response Adapter，因此扩展某一层时不需要重写其他层。

## 最小示例

```kotlin
// Application / AppDelegate
NetworkClients.initialize(
    defaultClient = createNetworkClient("https://api.example.com") {
        commonHeaders { mapOf("X-App-Version" to appVersion) }
        responseCache()
        coalesceRequests()
    },
)

@NetworkService
interface ArticleApi {
    @GET("/v1/articles/{id}")
    @Cache(maxAgeSeconds = 60, staleIfErrorSeconds = 3_600)
    fun detail(@Path("id") id: String): NetworkCall<ArticleDto>
}

private val api = createArticleApi()

val state = api.detail(articleId)
    .retryWhen(NetworkRetryPolicy(maxAttempts = 3)) { it.retryable }
    .asFlow()
```

## 已覆盖的能力

| 领域 | 能力 |
| --- | --- |
| 请求 | 7 种 HTTP 方法、动态 URL、Query/Header、JSON、Form、Multipart、ByteArray、进度 |
| 结果 | `NetworkCall`、`NetworkResult`、`NetworkState`、结构化失败、统一业务外壳 |
| 缓存 | Cache First、Network First、Cache Only、SWR、过期兜底、标签失效 |
| 韧性 | 安全重试、重试预算、请求合并、并发背压、优先级、限流、熔断、离线快速失败 |
| 安全 | HTTPS 动态地址、同源重定向、证书 Pin、响应上限、Inspector 脱敏 |
| 工程 | Koin、Inspector、指标、SSE、WebSocket SPI、脚本化测试、BOM、ABI 检查 |

!!! tip "不知道从哪里开始？"

    先阅读[安装与 KSP](getting-started/installation.md)，然后照着[快速开始](getting-started/quick-start.md)
    跑通第一个接口。遇到具体需求时，再从左侧导航查对应注解或操作符。
