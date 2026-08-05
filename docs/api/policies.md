# 请求策略注解

策略既可以写进接口 Contract，也可以在 `NetworkCall` 调用处覆盖。稳定且对所有调用者一致的规则写注解；只与
某次业务操作有关的规则写调用链。

## Timeout

```kotlin
@Timeout(15)
@GET("/v1/profile")
fun profile(): NetworkCall<ProfileDto>
```

范围是 1..300 秒。优先级：Client 默认值 < `@Timeout` < `.timeout(seconds)`。

## Retry

```kotlin
@Retry(
    maxAttempts = 3,
    initialDelayMillis = 300,
    maxDelayMillis = 5_000,
    multiplier = 2.0,
    jitterRatio = 0.2,
)
@GET("/v1/config")
fun config(): NetworkCall<ConfigDto>
```

| 参数 | 约束 | 含义 |
| --- | --- | --- |
| `maxAttempts` | 1..6 | 包含第一次请求的总尝试次数 |
| `initialDelayMillis` | ≥ 0 | 第一次重试前延迟 |
| `maxDelayMillis` | ≥ initial | 退避上限 |
| `multiplier` | ≥ 1 | 指数倍数 |
| `jitterRatio` | 0..1 | 随机抖动，避免惊群 |
| `retryUnsafeMethods` | 默认 false | 是否允许无幂等键的写请求重试 |

GET/HEAD/OPTIONS 默认可按策略重试。POST/PUT/PATCH/DELETE 应提供 `@IdempotencyKey`；只有确认服务端支持幂等
时，才开启 `retryUnsafeMethods`。

## Cache

```kotlin
@Cache(
    maxAgeSeconds = 60,
    staleIfErrorSeconds = 3_600,
    mode = NetworkCacheMode.NETWORK_FIRST,
    tags = ["profile"],
)
@GET("/v1/profile")
fun profile(): NetworkCall<ProfileDto>
```

`@Cache` 只允许 GET：

| 参数 | 范围 | 说明 |
| --- | --- | --- |
| `maxAgeSeconds` | 1..86400 | 新鲜缓存有效期 |
| `staleIfErrorSeconds` | 0..604800 | 在线失败后可接受的过期窗口 |
| `mode` | 5 种模式 | 读取顺序与是否触网 |
| `staleWhileRevalidateSeconds` | 0..604800 | SWR 后台刷新窗口 |
| `tags` | 每项 1..64 字符 | 给缓存项打标签 |

Client 必须先添加 `responseCache()`，策略才会实际读写缓存。

## InvalidateCache

```kotlin
@PATCH("/v1/profile")
@InvalidateCache("profile", "home")
fun update(@Body request: UpdateProfile): NetworkCall<ProfileDto>
```

只有写请求返回 2xx 后才失效标签。失败请求不会提前删除仍可用的缓存。

## Priority 与 Tags

```kotlin
@Priority(NetworkPriority.HIGH)
@Tags("screen:checkout", "rate:orders")
@POST("/v1/orders")
fun create(@Body request: CreateOrder): NetworkCall<OrderDto>
```

优先级为 `LOW`、`NORMAL`、`HIGH`、`IMMEDIATE`。只有 Client 启用了 `priorityQueue()`，优先级才参与排队；
同优先级仍保持 FIFO。

Tags 不进入 URL 或 Header，可用于：

- Inspector/指标按业务场景聚合。
- `rateLimit()` 通过 `rate:` 前缀选择令牌桶。
- 自定义 Interceptor 做路由、采样或诊断。

标签允许字母、数字和 `-_.:/`，长度 1..64。

## Redirects

```kotlin
@Redirects(
    enabled = true,
    maxRedirects = 3,
    allowCrossOrigin = false,
)
@GET("/v1/export")
fun export(): NetworkCall<ExportDto>
```

默认最多 5 次且只允许同源。跨域必须显式开启；Android/iOS 在跨域重定向时会移除 Authorization 与 Cookie
等敏感 Header。动态下载地址通常优先使用明确的 `@Url`，不要无限跟随未知重定向。

## Headers 与 ResponseLimit

```kotlin
@Headers("Accept: application/vnd.company.v2+json")
@ResponseLimit(4 * 1024 * 1024)
@GET("/v2/items")
fun items(): NetworkCall<List<ItemDto>>
```

`@Headers` 适合固定协议 Header；Token、Locale 和 Request ID 应通过动态参数或公共 Header Interceptor 注入。
`@ResponseLimit` 防止错误页或恶意响应占满内存，最大为 20MiB。

## 调用链覆盖

```kotlin
api.profile()
    .timeout(5)
    .noRetry()
    .networkFirst(maxAgeSeconds = 30, staleIfErrorSeconds = 300)
    .priority(NetworkPriority.IMMEDIATE)
    .tag("screen:profile")
```

覆盖只影响这个调用对象，不会修改同一 API 的后续请求。
