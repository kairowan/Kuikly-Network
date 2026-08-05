# 自定义 Interceptor

## 最小实现

```kotlin
class TenantInterceptor(
    private val tenantId: suspend () -> String,
) : NetworkInterceptor {
    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        val request = chain.request.copy(
            headers = chain.request.headers + ("X-Tenant-ID" to tenantId()),
        )
        return chain.proceed(request)
    }
}
```

```kotlin
val client = createNetworkClient(baseUrl) {
    addInterceptor(TenantInterceptor(session::tenantId))
}
```

请求模型不可变，修改时使用 `copy`。不要原地维护共享 Header Map。

## 响应处理

```kotlin
class ServerTimeInterceptor(
    private val updateClock: (String) -> Unit,
) : NetworkInterceptor {
    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        val response = chain.proceed()
        response.headers.entries
            .firstOrNull { it.key.equals("Date", ignoreCase = true) }
            ?.value
            ?.let(updateClock)
        return response
    }
}
```

旁路逻辑不应因为解析一个非关键 Header 失败而覆盖成功响应。

## 短路请求

```kotlin
class MaintenanceInterceptor(
    private val maintenance: () -> Boolean,
) : NetworkInterceptor {
    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        if (maintenance()) throw NetworkTransportException(
            NetworkFailure(
                code = "maintenance",
                category = NetworkFailureCategory.CIRCUIT_OPEN,
                message = "服务维护中",
                retryAfterMillis = 30_000,
            ),
        )
        return chain.proceed()
    }
}
```

公共层可识别的失败应使用 `NetworkTransportException(NetworkFailure(...))`，不要抛出只有某个平台认识的异常。

## 按 Tag 控制

```kotlin
val samplingInterceptor = NetworkInterceptor { chain ->
    if ("telemetry:full" in chain.request.tags) {
        telemetry.recordStart(chain.request.method, chain.request.relativePath)
    }
    chain.proceed()
}
```

Tag 不进入传输，适合控制签名版本、日志采样、Mock 路由或实验分组。

## 有状态 Interceptor 与取消

```kotlin
class BackgroundWorkInterceptor : NetworkInterceptor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse =
        chain.proceed()

    override fun cancelAll() {
        scope.coroutineContext.cancelChildren()
    }
}
```

如果拦截器持有 Deferred、后台刷新或队列，必须覆盖 `cancelAll()`。无状态实现保持默认即可。

## 取消与异常规则

```kotlin
override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse = try {
    chain.proceed()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    throw NetworkTransportException(mapError(error))
}
```

永远先重新抛出 `CancellationException`。把取消映射成普通失败会导致页面退出后仍执行重试或错误提示。

## 顺序测试

使用 `ScriptedNetworkEngine` 记录最终请求：

```kotlin
val engine = ScriptedNetworkEngine(listOf(respond("{}")))
val client = engine.client {
    addInterceptor(TenantInterceptor { "tenant-a" })
}

client.executeRaw(request)

assertEquals("tenant-a", engine.recordedRequests().single().headers["X-Tenant-ID"])
```

一个精确测试比在每个业务调用处重复 Header 断言更能固定拦截器 Contract。
