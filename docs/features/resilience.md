# 重试、限流与熔断

韧性组件解决不同问题，不应“全部打开然后用默认值”。先识别系统瓶颈，再选择最少的策略。

## 有限重试

```kotlin
val retryPolicy = NetworkRetryPolicy(
    maxAttempts = 3,
    initialDelayMillis = 300,
    maxDelayMillis = 5_000,
    multiplier = 2.0,
    jitterRatio = 0.2,
)

api.config()
    .retryWhen(retryPolicy) { it.retryable }
```

安全规则：

- `maxAttempts` 包括第一次请求。
- 取消不重试。
- 非幂等写请求默认不重试。
- 服务端 `Retry-After` 会参与延迟。
- 抖动用于避免大量客户端同时重试。

## 共享重试预算

```kotlin
val retryBudget = TokenBucketRetryBudget(
    capacity = 20,
    refillTokens = 5,
    refillIntervalMillis = 1_000,
)

val client = createNetworkClient(baseUrl) {
    retryBudget(retryBudget)
}
```

一次普通首请求不消耗预算，每次额外重试消耗 Token。服务故障时预算能阻止所有接口同时产生重试风暴。

## 并发背压

```kotlin
limitConcurrency(
    maxConcurrentRequests = 8,
    maxQueueWaitMillis = 1_000,
)
```

最多 8 个真实请求。等待超过上限会返回 `CLIENT_THROTTLED/client_throttled`，而不是无限积压协程。

适合优先保护客户端资源但不关心请求优先级的场景。

## 优先级队列

```kotlin
priorityQueue(
    NetworkPriorityPolicy(
        maxConcurrentRequests = 8,
        maxQueuedRequests = 128,
        maxQueueWaitMillis = 5_000,
    ),
)
```

用户可见的 `IMMEDIATE/HIGH` 请求先出队，同优先级保持 FIFO。队列满或超时会返回结构化限流失败。

!!! warning

    `priorityQueue()` 已经包含并发限制，一般不要再和 `limitConcurrency()` 叠加，否则会形成两层队列。

## 客户端令牌桶限流

```kotlin
rateLimit(
    NetworkRateLimitPolicy(
        capacity = 20,
        refillTokens = 10,
        refillIntervalMillis = 1_000,
    ),
)
```

默认按 `rate:` Tag 或第一个路径段分桶：

```kotlin
api.search(keyword).tag("rate:search")
```

限流解决客户端突发流量，不替代服务端限流。被拒绝时返回 `client_rate_limited` 和 `retryAfterMillis`。

## 三态熔断

```kotlin
circuitBreaker(
    policy = NetworkCircuitBreakerPolicy(
        failureThreshold = 5,
        openDurationMillis = 30_000,
        maxTrackedCircuits = 64,
    ),
)
```

熔断器只统计传输异常和 5xx：

```text
Closed --连续失败达到阈值--> Open
Open --等待 openDuration--> Half-open（只放一个探测）
Half-open --成功--> Closed
Half-open --失败--> Open
```

4xx 和主动取消不会污染服务健康状态。默认按路径前两个资源段分组，动态 ID 不会无限创建 Circuit。可通过
`keySelector` 按服务或业务 Tag 自定义。

## 离线快速失败

```kotlin
connectivity(platformConnectivityProvider)
```

当系统已经确认离线时，直接返回 `network_offline`，避免等待 DNS/连接超时。Provider 是公开接口，由 Android、
iOS 或 Harmony 宿主接入平台网络监听。

## 推荐顺序

```text
缓存/请求合并 → 优先级或并发 → 客户端限流 → 熔断 → Engine
```

- 缓存命中不应消耗传输许可。
- 熔断尽量靠近真实传输，缓存和本地降级不应被统计为服务失败。
- 重试在 `NetworkCall` 层执行，每次尝试都会重新经过 Interceptor 链，因此仍受限流和熔断保护。
