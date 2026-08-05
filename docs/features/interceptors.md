# 拦截器与鉴权

`NetworkInterceptor` 处理横切请求逻辑。它接收不可变 `NetworkRawRequest`，可以修改副本、短路返回、调用下游，
也可以在响应返回后做处理。

## 推荐配置

```kotlin
val client = createNetworkClient(baseUrl) {
    commonHeaders { request -> appHeaders(request) }
    bearerAuthentication(
        currentToken = session::accessToken,
        refreshToken = session::refresh,
        shouldAuthenticate = { request -> "public" !in request.tags },
    )
    responseCache(cacheStore)
    coalesceRequests()
    priorityQueue()
    rateLimit()
    circuitBreaker()
    cookies(cookieStore)
    contentNegotiation("application/json")
    addInterceptor(companySigner)
}
```

## 公共 Header

```kotlin
commonHeaders { request ->
    buildMap {
        put("X-App-Version", appVersion)
        put("X-Platform", platformName)
        request.tags.firstOrNull { it.startsWith("locale:") }
            ?.substringAfter(':')
            ?.let { put("Accept-Language", it) }
    }
}
```

Provider 是挂起函数，可以安全读取异步会话状态。Header 会覆盖同名旧值并拒绝换行注入。

## Bearer 自动刷新

```kotlin
bearerAuthentication(
    currentToken = tokenStore::readAccessToken,
    refreshToken = { expired -> authRepository.refresh(expired) },
    shouldAuthenticate = { "anonymous" !in it.tags },
)
```

工作过程：

1. 请求已有显式 `Authorization` 时不覆盖。
2. 读取当前 Token 并发送第一次请求。
3. 非 401 直接返回。
4. 多个并发 401 通过 Mutex 收敛成一次刷新。
5. 如果其他请求已经刷新出新 Token，直接复用。
6. 刷新成功后每个请求最多重放一次。

刷新失败保留原 401，不会无限递归。公开接口可通过 Tag 或 `shouldAuthenticate` 排除。

## Cookie 与内容协商

```kotlin
cookies(MemoryNetworkCookieStore(maxCookies = 256))
contentNegotiation("application/json", "application/problem+json")
```

默认 Cookie Store 只在内存中。需要跨进程持久化时实现 `NetworkCookieStore`，并负责加密和过期清理。
Content Negotiation 会写默认 Accept，并校验响应 Content-Type 是否在允许范围。

## 拦截器顺序

Builder 按添加顺序进入请求，按相反顺序返回响应。推荐思路：

```text
公共 Header / 鉴权
  → 缓存
    → 请求合并
      → 优先级/并发/限流/熔断
        → 自定义签名或传输前拦截器
          → Engine
```

- 鉴权要在缓存键计算前完成，避免跨用户复用缓存。
- 缓存和合并放在并发限制前，命中缓存不占真实传输许可。
- 签名如果依赖所有最终 Header，应靠近 Engine。
- Inspector 放在哪里，决定它看到修改前还是修改后的请求。

顺序没有对所有业务唯一正确的答案，但应该在初始化处集中声明并通过测试固定。

## 取消

有状态 Interceptor 可以覆盖 `cancelAll()`。`NetworkClient.cancelAll()` 会依次取消 Interceptor 持有的后台工作，
再取消 Engine 请求；Android/iOS Client 取消后仍可继续发起新请求。

自定义写法见[自定义 Interceptor](../extensions/interceptor.md)。
