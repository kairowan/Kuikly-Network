# 缓存与请求合并

缓存默认关闭。只有 Client 安装 `responseCache()` 且 GET 调用配置了缓存策略时才读写。

## 安装缓存

```kotlin
val store = MemoryNetworkCacheStore(maxEntries = 128)

val client = createNetworkClient(baseUrl) {
    responseCache(store)
}
```

内存 Store 有容量上限。需要磁盘、数据库或加密缓存时，实现 `NetworkPersistentCacheStore`，不要把具体数据库依赖
塞进 Core。

## 五种模式

| 模式 | 行为 | 场景 |
| --- | --- | --- |
| `NETWORK_ONLY` | 不读写缓存 | 强一致、敏感数据 |
| `CACHE_FIRST` | 新鲜缓存优先，没有则网络 | 基础配置、低频列表 |
| `NETWORK_FIRST` | 网络优先，失败后允许缓存 | 详情页、希望尽量新鲜 |
| `CACHE_ONLY` | 只读新鲜缓存，缺失返回 `cache_miss` | 离线入口、预热检查 |
| `STALE_WHILE_REVALIDATE` | 立即返回缓存并后台刷新 | 首页、内容流 |

```kotlin
api.home().cacheFirst(maxAgeSeconds = 60)
api.detail(id).networkFirst(60, staleIfErrorSeconds = 3_600)
api.feed().staleWhileRevalidate(30, staleWhileRevalidateSeconds = 300)
api.cachedConfig().cacheOnly(maxAgeSeconds = 86_400)
```

`staleIfError` 只在传输异常、408、429 或 5xx 等可用旧数据的情况下兜底；普通 4xx 不会被旧缓存掩盖。

## 缓存来源

`NetworkResult.Success.source` / `NetworkState.Success.source` 会明确标记：

- `NETWORK`
- `MEMORY_CACHE`
- `STALE_CACHE`
- `LOCAL_FALLBACK`

UI 可以根据来源显示“离线内容”或“正在刷新”，不需要猜测。

## 标签失效

```kotlin
@GET("/v1/articles/{id}")
@Cache(maxAgeSeconds = 300, tags = ["articles"])
fun detail(@Path("id") id: String): NetworkCall<ArticleDto>

@PATCH("/v1/articles/{id}")
@InvalidateCache("articles", "home")
fun update(@Path("id") id: String, @Body body: PatchArticle): NetworkCall<ArticleDto>
```

写请求只有 2xx 时才按标签失效。也可以在调用链使用 `.cacheTags()` 和 `.invalidateCacheTags()`。

标签适合按资源族失效。若需要精确到单个 ID，可以使用 `article:$id`，但要注意标签总量和持久化 Store 的索引成本。

## 请求合并

```kotlin
val client = createNetworkClient(baseUrl) {
    commonHeaders(session::headers)
    responseCache()
    coalesceRequests()
}
```

同一 Client 中并发发生的相同 GET 会共享一次真实请求。合并键包含方法、路径、Header、Body 和传输策略，因此
不同 Token/用户不会错误复用。

取消语义：

- 每个调用方只取消自己的等待。
- 最后一个等待者离开时才取消真实请求。
- `client.cancelAll()` 会取消全部共享请求。

自定义选择条件：

```kotlin
coalesceRequests { request ->
    request.method == NetworkMethod.GET && "no-coalesce" !in request.tags
}
```

## 缓存键与敏感数据

鉴权 Header 应在缓存/合并拦截器之前加入。即使 Store 使用哈希键，也不应把原始 Token 写入日志或磁盘索引。

## Store 失败策略

缓存读取、写入和标签失效的普通异常不会改变在线请求结果；协程取消仍向外传播。缓存是旁路能力，不能因为磁盘
暂时故障让成功的在线响应变成失败。
