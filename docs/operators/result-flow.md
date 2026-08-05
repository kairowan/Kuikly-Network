# Result 与 Flow 操作符

请求已经执行得到 `NetworkResult`，或已经转换为 `Flow<NetworkState<T>>` 后，仍可以使用轻量操作符完成 UI
和领域层转换。

## NetworkResult 操作符

| 操作符 | 作用 |
| --- | --- |
| `mapData` | 只转换成功数据并保留状态码、Header、来源等元数据 |
| `flatMapData` | 成功后组合另一个 Result，失败短路 |
| `mapFailure` | 只转换失败 |
| `recover` | 失败恢复为 `LOCAL_FALLBACK` 成功数据 |
| `onSuccess` | 成功旁路操作 |
| `onFailure` | 失败旁路操作 |
| `getOrNull` | 失败返回 null |
| `getOrElse` | 失败使用计算出的默认值 |
| `getOrThrow` | 失败抛 `NetworkRequestException` |
| `fold` | 成功/失败折叠为同一业务类型 |

```kotlin
val uiModel = api.detail(id).await()
    .mapData(ArticleDto::toUiModel)
    .onFailure(logger::record)
    .getOrElse { ArticleUiModel.offline(it.code) }
```

`NetworkResult.recover` 总会提供一个值；如果只有特定错误才允许降级，先用 `when`，或者在请求执行前使用
`NetworkCall.recover { ... }` 的可空返回形式。

## Flow 状态操作符

| 操作符 | 作用 | 是否吞状态 |
| --- | --- | :---: |
| `mapData` | 转换 `Success.data` | 否 |
| `onData` | 监听成功数据 | 否 |
| `onLoading` | 监听 Loading | 否 |
| `onFailure` | 监听 Error | 否 |
| `mapFailure` | 改写 Error.failure | 否 |
| `recoverData` | 特定错误恢复成 `LOCAL_FALLBACK` Success | 只替换被恢复的 Error |
| `executeOn` | 使用 `flowOn` 切换上游执行上下文 | 否 |

```kotlin
api.detail(id)
    .asFlow()
    .onLoading { analytics.loading("article") }
    .onData(cache::save)
    .mapData(ArticleDto::toUiModel)
    .recoverData { failure ->
        cache.read(id).takeIf {
            failure.category == NetworkFailureCategory.CONNECTIVITY
        }
    }
    .onFailure(logger::record)
```

这些操作符保持 `Loading → Success/Error` 的单向流，不会把失败变成完成但没有任何值的空流。

## StateFlow

```kotlin
val state: StateFlow<NetworkState<ArticleDto>> = api.detail(id)
    .asStateFlow(viewModelScope)
```

默认共享策略是 `SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)`，初始状态为 `Loading`。适合多个
UI 观察者共享同一次上游执行。需要常驻同步时显式传入其他 `SharingStarted`，不要无意中让页面请求永久运行。

## executeOn

```kotlin
api.items()
    .asFlow()
    .mapData(::expensiveMapping)
    .executeOn(Dispatchers.Default)
```

语义与 `Flow.flowOn` 一致：切换其上游请求、解析和操作符上下文，不会强制下游 UI 回调运行在某个线程。

## 错误示例

```kotlin
// 不推荐：丢失结构化失败和 Loading 语义
api.detail(id).asFlow().mapNotNull { (it as? NetworkState.Success)?.data }

// 推荐：状态方向保持完整
api.detail(id).asFlow().mapData(ArticleDto::toUiModel)
```

如果页面只需要最终结果而不需要 Loading，使用 `asResultFlow()`，意图比手工过滤更明确。
