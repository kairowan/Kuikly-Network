# NetworkCall 操作符

`NetworkCall<T>` 是一个可重复执行、不可变的调用描述。操作符只生成新 Call；直到 `await`、`awaitData`、
收集 Flow 或 `enqueue` 时才执行。

## 执行终点

| 操作符 | 返回 | 使用场景 |
| --- | --- | --- |
| `await()` | `NetworkResult<T>` | 显式处理成功与失败，默认推荐 |
| `awaitData()` | `T` | 异常式架构，失败抛 `NetworkRequestException` |
| `fold()` | 业务类型 | 在一次表达式中合并成功/失败分支 |
| `asFlow()` | `Flow<NetworkState<T>>` | UI 收集 Loading/Success/Error |
| `asResultFlow()` | `Flow<NetworkResult<T>>` | 不需要 Loading 的冷结果流 |
| `enqueue(scope, ...)` | `Job` | 回调式边界或兼容旧代码 |

```kotlin
val title = api.detail(id).fold(
    onSuccess = { it.data.title },
    onFailure = { "加载失败：${it.code}" },
)
```

`asFlow()` 和 `asResultFlow()` 都是冷流：每次收集重新执行。取消收集保留协程取消语义。

## 数据转换

| 操作符 | 含义 | 典型场景 |
| --- | --- | --- |
| `map` | 同步转换成功数据 | DTO → Domain Model |
| `mapSuspend` | 挂起转换成功数据 | 保存数据库、读取其他异步数据 |
| `flatMap` | 成功后执行另一个 NetworkCall | 登录后加载资料、创建后查询详情 |
| `validate` | 成功数据不满足条件时转结构化失败 | HTTP 200 内的业务不变量 |
| `mapFailure` | 改写失败，成功不变 | 映射领域错误码 |

```kotlin
fun loadArticle(id: String): NetworkCall<Article> = api.detail(id)
    .validate(
        predicate = { it.id.isNotBlank() },
        failure = { NetworkFailure("invalid_article", NetworkFailureCategory.VALIDATION) },
    )
    .map { dto -> Article(dto.id, dto.title.trim()) }
```

`map`/`mapSuspend` 的普通异常会转成结构化 mapping failure；`CancellationException` 不会被吞掉。

### 串联请求

```kotlin
authApi.login(credentials)
    .flatMap { session -> profileApi.profile(session.userId) }
    .map { profile -> profile.toDomain() }
```

第二个调用只在第一个成功后创建并执行；第一个失败会短路。彼此独立、没有数据依赖的请求应使用
`awaitAllNetwork` 并行执行，不要用 `flatMap` 人为串行化。

## 失败恢复

| 操作符 | 行为 | 适用场景 |
| --- | --- | --- |
| `recover` | 失败转本地数据；返回 null 保留失败 | 明确允许的本地兜底 |
| `recoverWith` | 失败后切换到另一个 Call | 备用服务/降级接口 |
| `fallbackTo` | 固定备用 Call | 主备接口 |
| `onSuccess` | 成功副作用，不改变数据 | 写轻量缓存、埋点 |
| `onFailure` | 失败旁路监听，不吞失败 | 日志、统一提示事件 |

```kotlin
remoteApi.config()
    .recover { failure ->
        localConfig.takeIf { failure.category == NetworkFailureCategory.CONNECTIVITY }
    }
```

```kotlin
primaryApi.region()
    .recoverWith { failure ->
        backupApi.region().takeIf { failure.retryable }
    }
```

降级数据的 `source` 会是 `LOCAL_FALLBACK`。不要对鉴权、TLS 或数据校验失败无条件返回旧数据。

## 重试

| 操作符 | 含义 |
| --- | --- |
| `retry(policy)` | 使用完整 `NetworkRetryPolicy` |
| `retry(maxAttempts, ...)` | 直接配置指数退避参数 |
| `retryWhen(policy, predicate)` | 只有失败满足业务条件才重试 |
| `noRetry()` | 明确关闭重试 |
| `retryBudget(budget)` | 多个调用共享令牌预算，阻止故障风暴 |
| `idempotencyKey(value)` | 为写请求添加幂等键 |

```kotlin
api.sync(request)
    .idempotencyKey(syncId)
    .retryWhen(
        policy = NetworkRetryPolicy(maxAttempts = 3),
        predicate = { it.retryable && it.statusCode != 401 },
    )
    .retryBudget(sharedRetryBudget)
```

`maxAttempts` 包含第一次调用。库会读取 `retryAfterMillis` 并应用指数退避与抖动。取消不会重试；无幂等键的
写请求默认不重试。

## 缓存

| 操作符 | 读取顺序 |
| --- | --- |
| `cacheFirst(maxAge)` | 新鲜缓存优先，否则网络 |
| `networkFirst(maxAge, staleIfError)` | 网络优先，失败后缓存 |
| `staleIfError(maxAge, staleWindow)` | 缓存优先，在线失败可用过期缓存 |
| `staleWhileRevalidate(maxAge, window)` | 返回缓存并后台刷新 |
| `cacheOnly(maxAge)` | 只读新鲜缓存，不触网 |
| `noCache()` | 当前调用禁止缓存 |
| `cache(policy)` | 完整策略对象 |
| `cacheTags(...)` | 给成功响应缓存打标签 |
| `invalidateCacheTags(...)` | 写请求成功后失效标签 |

```kotlin
api.home()
    .staleWhileRevalidate(maxAgeSeconds = 30, staleWhileRevalidateSeconds = 300)
    .cacheTags("home", "feed")
```

Client 必须先安装 `responseCache()`。完整模式选择见[缓存与请求合并](../features/cache.md)。

## 单次请求策略

| 操作符 | 用途 |
| --- | --- |
| `timeout(seconds)` | 覆盖超时 |
| `responseLimit(bytes)` | 限制响应体 |
| `priority(value)` | 调度优先级 |
| `tag(value)` / `tags(values)` | 添加业务标签 |
| `redirects(policy)` | 覆盖重定向策略 |
| `progress(listener)` | 上传/下载进度 |
| `header(name, value)` / `headers(map)` | 添加动态 Header |

```kotlin
api.download(id)
    .timeout(120)
    .responseLimit(16L * 1024 * 1024)
    .priority(NetworkPriority.HIGH)
    .tag("screen:downloads")
    .header("X-Trace-ID", traceId)
    .progress(::renderProgress)
```

Header 合并忽略名称大小写，拒绝换行符。Tags 不进入传输内容。

## 操作符顺序

策略类操作符主要是复制 `NetworkRequestOptions`，通常顺序不改变结果；转换与恢复操作符有业务顺序：

```kotlin
api.detail(id)
    .retryWhen(policy) { it.retryable } // 先完成有限重试
    .recover(::readLocal)               // 最终仍失败才降级
    .map(::toDomain)                    // 网络或本地数据统一映射
```

把 `recover` 放在错误监控之前还是之后，决定监控看到原始失败还是恢复后的成功，应按业务意图明确选择。
