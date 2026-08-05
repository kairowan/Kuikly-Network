# Kuikly 页面接入

Kuikly 场景同时存在“应用级网络客户端”和“页面级协程生命周期”。两者应该分开管理：客户端可以全局复用，页面只取消自己发起的任务。

## 页面级调用

已有 `Pager` 时可以创建绑定当前页面的客户端：

```kotlin
private val client = createKuiklyNetworkClient(
    pager = pager,
    baseUrl = "https://api.example.com/",
)
```

发起请求：

```kotlin
pager.networkScope.launch(userApi.profile()) { result ->
    result.onSuccess { profile -> render(profile) }
        .onFailure { error -> showError(error) }
}
```

页面销毁时释放页面作用域：

```kotlin
override fun onDestroy() {
    closeNetworkScope()
    super.onDestroy()
}
```

## 离开页面是否取消请求

取决于请求在哪个作用域执行：

| 调用方式 | 离开页面 | 适合场景 |
| --- | --- | --- |
| `pager.networkScope.launch(...)` | 取消页面任务 | 列表刷新、详情加载、搜索 |
| 应用级 `CoroutineScope` 调用全局客户端 | 继续执行 | 上传、埋点、同步、跨页任务 |
| `NetworkCall.enqueue(scope, ...)` | 跟随传入 `scope` | 由调用者明确控制生命周期 |

应用级客户端不要注册到页面作用域中。正确做法是在宿主启动时安装 `NetworkClients`，页面仅通过生成 API 取得请求对象，并决定把它运行在哪个作用域。

```kotlin
applicationScope.launch {
    createUploadApi().upload(file).await()
}
```

!!! tip "取消是协程语义"
    `NetworkCall` 本身是惰性的请求描述。真正决定生命周期的是执行它的协程，不是 API 接口所在的类。

## 多 Base URL

为不同服务声明命名客户端：

```kotlin
@NetworkService(client = "content")
interface ContentApi {
    @GET("articles")
    fun articles(): NetworkCall<List<Article>>
}
```

应用初始化：

```kotlin
NetworkClients.initialize(
    defaultClient = defaultClient,
    namedClients = mapOf(
        "content" to contentClient,
        "upload" to uploadClient,
    ),
)
```

页面直接调用 `createContentApi()`，无需传入客户端。

## 不使用全局注册表

需要更强隔离时仍可显式注入客户端：

```kotlin
class ArticleRepository(client: NetworkClient) {
    private val api = createContentApi(client)
}
```

全局注册和显式注入并不冲突：前者适合应用默认配置，后者适合测试、SDK 多实例或严格依赖注入环境。
