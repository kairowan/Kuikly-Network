# 并行、批处理与轮询

编排函数只组合 `NetworkCall` 和协程标准能力，不引入新的任务调度框架。

## 有界并行

```kotlin
val results: List<NetworkResult<UserDto>> = userIds
    .map(api::detail)
    .awaitAllNetwork(maxConcurrency = 8)
```

特点：

- 最多同时执行 `maxConcurrency` 个调用，范围 1..64。
- 返回顺序与输入顺序一致，不按完成顺序打乱。
- 单个失败只表现为对应位置的 `NetworkResult.Failure`，不会让其他结果丢失。
- 外层协程取消会取消全部子调用。

适合批量补齐详情、多个独立 Dashboard 卡片。不要把并发值直接设为输入数量。

## 批处理

```kotlin
val results = itemIds.executeNetworkBatches(
    batchSize = 50,
    maxConcurrentBatches = 2,
) { batch ->
    api.fetchBatch(BatchRequest(batch))
}
```

`batchSize` 范围 1..1000。函数先 `chunked`，再用有界并行执行批次。适合服务端支持批量 ID、客户端输入可能
很大且需要避免 URL/Body 过长的场景。

代码等价于：

```kotlin
itemIds.chunked(50)
    .map { api.fetchBatch(BatchRequest(it)) }
    .awaitAllNetwork(maxConcurrency = 2)
```

## 轮询

```kotlin
val policy = NetworkPollingPolicy(
    intervalMillis = 2_000,
    maxPolls = 30,
    stopOnFailure = false,
)

pollNetwork(
    policy = policy,
    stopWhen = { task -> task.finished },
    callFactory = { api.task(taskId) },
).collect(::renderTaskState)
```

| 参数 | 范围 | 说明 |
| --- | --- | --- |
| `intervalMillis` | 100..300000ms | 两次尝试之间的固定间隔 |
| `maxPolls` | 1..10000 | 最多请求次数 |
| `stopOnFailure` | Boolean | 第一次失败是否停止 |

Flow 启动时先发 `Loading`。每次成功/失败都会发一个状态；成功满足 `stopWhen` 后立即结束。取消收集会同时取消
当前请求和后续 `delay`，不需要额外 Timer。

### 轮询还是 SSE/WebSocket

| 场景 | 选择 |
| --- | --- |
| 几秒到几十秒完成的异步任务 | 有限轮询 |
| 服务端没有推送协议、状态变化频率低 | 轮询 |
| 长期高频消息、聊天、行情 | WebSocket |
| 单向服务端事件流 | SSE |

轮询必须有 `maxPolls`，否则页面忘记取消时可能长期占用网络和电量。

## 转成 StateFlow

```kotlin
val taskState = pollNetwork(
    stopWhen = TaskDto::finished,
    callFactory = { api.task(id) },
).stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = NetworkState.Loading,
)
```

`NetworkCall.asStateFlow` 是单个调用的便捷形式；轮询等普通 Flow 使用标准 `stateIn` 即可。

## 选择串行还是并行

```text
后一个请求依赖前一个结果 ── flatMap / 顺序 await
请求彼此独立             ── awaitAllNetwork
服务端支持批量接口        ── executeNetworkBatches
同一状态重复查询          ── pollNetwork
```

优先使用服务端批量接口；N 个独立请求即使有限并发，也会比一个批量请求产生更多握手、Header 和服务端调度开销。
