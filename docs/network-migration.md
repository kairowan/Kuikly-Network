# 网络层迁移指南

## 1. 字符串路径迁移为 Contract

把散落的 `"/api/..."`、请求参数 Map 和手工 JSON 解析移动到一个 `@NetworkService` 接口。业务调用只保留
DTO 参数与 `NetworkCall<T>`，不再拼接方法、URL 或 Header。

## 2. 旧 Kuikly JSON 调用

`network-kuikly` 保留 `LegacyJsonNetworkApi` 与 facade，旧页面可以继续运行。新接口先迁移到注解 Contract，
再逐页删除旧 facade；不要在 `network-core` 中引入 Kuikly 类型。

## 3. Retrofit 接口

注解名称和参数模型与 Retrofit 接近，但生成器位于 commonMain，返回类型应使用 `NetworkCall<T>`、
`NetworkResult<T>` 或 `NetworkState<T>`。Android 独占 DTO 可选择 Gson，跨端 DTO 使用 Kotlin Serialization。

## 4. 平台请求封装

- Android 自定义拦截器继续通过注入的 OkHttpClient 接入。
- iOS 不再把 NSDictionary/JSONObject 传回公共层，统一实现 `NetworkEngine`。
- Kuikly 页面使用 `createKuiklyNetworkClient`，并在销毁时调用 `closeNetworkScope`。
- 鸿蒙在宿主注册 `CatchzoonNetworkModule`，业务 Contract 无需复制。

## 5. 错误与状态

不要匹配异常 message。分支使用 `NetworkFailure.code/category/retryable/statusCode`；UI 使用
`asFlow()` 获得 Loading、Success、Error，Repository 需要精确元数据时使用 `await()`。

## 6. 分阶段上线

1. 先用 `network-testing` 固定旧接口行为。
2. 迁移 Contract，并对比请求方法、路径、Header、正文和返回 DTO。
3. 仅对幂等接口开启重试和缓存。
4. 接入指标、Inspector 和灰度开关。
5. 删除最后一个旧调用者后再删除兼容 API。

