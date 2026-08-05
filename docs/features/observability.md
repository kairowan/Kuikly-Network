# Inspector 与指标

## Inspector

`network-inspector` 保存有界的内存请求快照，不保存请求/响应正文，只记录字节数。

```kotlin
val inspector = NetworkInspector(
    maxEntries = 100,
    redaction = NetworkRedactionPolicy(),
)

val client = createNetworkClient(baseUrl) {
    inspector(inspector)
}
```

```kotlin
inspector.exchanges.collect { exchanges ->
    renderDebugPanel(exchanges)
}
```

单条 `NetworkExchange` 包含方法、脱敏路径、Header、请求/响应字节数、标签、时间、状态码和结构化失败。

## 默认脱敏

默认 Header：

- `Authorization`
- `Cookie`
- `Set-Cookie`
- `X-Api-Key`

默认 Query：

- `token`
- `access_token`
- `refresh_token`
- `api_key`
- `password`

自定义规则：

```kotlin
val policy = NetworkRedactionPolicy(
    headerNames = setOf("Authorization", "X-Company-Secret"),
    queryNames = setOf("token", "signature"),
)
```

Inspector 仅应用在 Debug/内部诊断构建。即使脱敏，也不要把完整快照未经审核上传第三方日志平台。

## 清理

```kotlin
inspector.clear()
```

`maxEntries` 范围 1..1000，超出时自动只保留最近记录，长期运行不会无限增长。

## 滚动指标

Core 自带不包含 URL 参数、Header 或正文的指标收集器：

```kotlin
val metrics = NetworkMetricsCollector(
    maxRecentSamples = 100,
    degradedP95Millis = 1_500,
)

val client = createNetworkClient(baseUrl) {
    metrics(metrics)
}
```

```kotlin
metrics.state.collect { snapshot ->
    renderNetworkQuality(snapshot.quality)
}
```

指标包括总数、在途、成功、失败、取消、缓存命中、过期缓存命中、平均耗时、P95、失败分类和
`GOOD/DEGRADED/OFFLINE` 质量等级。

## 自定义事件监听

```kotlin
addEventListener { event ->
    when (event) {
        is NetworkEvent.Completed -> telemetry.record(
            status = event.statusCode,
            durationMs = event.durationMillis,
            source = event.source.name,
        )
        is NetworkEvent.Failed -> telemetry.recordFailure(event.failure.category)
        else -> Unit
    }
}
```

`NetworkEvent` 有意不携带正文，路径仍可能包含业务标识；上传前应继续做路径归一化或采样。

## Inspector 与 EventListener 的区别

| 能力 | Inspector | EventListener/Metrics |
| --- | --- | --- |
| 单请求 Header/路径 | 有，自动脱敏 | 无 Header，事件只有 path |
| 正文 | 只记录字节数 | 不携带 |
| UI 调试列表 | 适合 | 不适合 |
| 聚合指标/上报 | 不适合 | 适合 |
| 生产默认启用 | 否 | 可在审核后启用 |
