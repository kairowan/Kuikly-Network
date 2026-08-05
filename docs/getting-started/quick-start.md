# 快速开始

下面用一个用户详情接口串起初始化、Contract、KSP 工厂和三种结果消费方式。

## 1. 定义 DTO

跨端 DTO 默认使用 Kotlin Serialization：

```kotlin
@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val avatar: String? = null,
)
```

`ignoreUnknownKeys = true`，服务端增加字段不会让旧客户端解析失败；`explicitNulls = false`，没有必要把缺失字段
强行当作显式 null。DTO 仍应给可选字段合理默认值。

## 2. 声明接口

```kotlin
@NetworkService
interface UserApi {
    @GET("/v1/users/{id}")
    @Headers("Accept-Language: zh-CN")
    @Timeout(15)
    fun detail(
        @Path("id") id: String,
        @Query("with_stats") withStats: Boolean = false,
        @RequestId requestId: String,
    ): NetworkCall<UserDto>
}
```

代码含义：

1. `@GET` 指定安全的相对路径。
2. `@Path` 对路径段做 URL 编码后替换 `{id}`。
3. `@Query` 追加查询参数；可空参数为 null 时省略。
4. `@RequestId` 写入 `X-Request-ID`，便于客户端和服务端日志关联。
5. `@Timeout` 只覆盖这个方法，不修改客户端默认值。

## 3. 应用启动时初始化

```kotlin
NetworkClients.initialize(
    defaultClient = createNetworkClient("https://api.example.com") {
        commonHeaders { request ->
            mapOf(
                "X-App-Version" to appVersion,
                "X-Platform" to platformName,
            )
        }
        contentNegotiation("application/json")
        responseCache()
        coalesceRequests()
    },
)
```

`NetworkClients.initialize` 只能执行一次，避免运行期间替换仍有请求在途的客户端。测试结束或确定应用退出时可调用
`NetworkClients.shutdown()` 清理。

## 4. 任意类中创建 API

```kotlin
class UserRepository {
    private val api = createUserApi()

    fun detail(id: String): NetworkCall<UserDto> = api.detail(
        id = id,
        requestId = createRequestId(),
    )
}
```

Repository 不需要接收 `NetworkClient`。如果项目使用显式 DI，也可以继续写：

```kotlin
class UserRepository(client: NetworkClient) {
    private val api = client.createUserApi()
}
```

## 5. 选择结果风格

=== "显式结果"

    ```kotlin
    when (val result = repository.detail(id).await()) {
        is NetworkResult.Success -> render(result.data)
        is NetworkResult.Failure -> renderError(result.error)
    }
    ```

    适合 Repository、UseCase 和需要读取状态码、请求 ID、缓存来源的代码。

=== "冷 Flow"

    ```kotlin
    repository.detail(id)
        .asFlow()
        .collect { state ->
            when (state) {
                NetworkState.Loading -> showLoading()
                is NetworkState.Success -> render(state.data)
                is NetworkState.Error -> renderError(state.failure)
            }
        }
    ```

    `asFlow()` 每次收集都会执行一次请求，取消收集会取消当前协程请求。

=== "直接数据/异常"

    ```kotlin
    try {
        render(repository.detail(id).awaitData())
    } catch (error: NetworkRequestException) {
        renderError(error.failure)
    }
    ```

    适合已有异常式基础设施，但不要用字符串匹配异常；读取结构化 `failure`。

## 6. 组合策略

```kotlin
repository.detail(id)
    .networkFirst(maxAgeSeconds = 60, staleIfErrorSeconds = 3_600)
    .retryWhen(NetworkRetryPolicy(maxAttempts = 3)) { failure ->
        failure.retryable && failure.category != NetworkFailureCategory.CANCELLED
    }
    .validate(
        predicate = { it.id.isNotBlank() },
        failure = { NetworkFailure("invalid_user", NetworkFailureCategory.VALIDATION) },
    )
    .onSuccess(userCache::put)
    .asFlow()
```

所有操作符返回新的 `NetworkCall`，原对象不变。只在最终调用 `await`、`awaitData`、收集 Flow 或 `enqueue`
时才真正执行网络请求。
