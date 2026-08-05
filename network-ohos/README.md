# network-ohos

`network-ohos` 是可独立引用或发布的 OHPM HAR。它把 Kuikly 的 `KRNetworkModule` 映射到
HarmonyOS `NetworkKit`，业务继续复用 `network-core` 的接口声明、KSP 生成代码、序列化、错误、
缓存、重试和状态流，不在 ArkTS 页面拼接 URL 或重复解析协议。

```json5
{
  "dependencies": {
    "@catchzoon/network-ohos": "file:../../network-ohos/build/default/outputs/default/network_ohos.har"
  }
}
```

宿主只需注册导出的 `CatchzoonNetworkModule`。适配器强制 HTTPS、关闭系统隐式缓存、校验头部与
响应上限，并在 Kuikly 页面销毁时终止所有在途 `HttpRequest`。

鸿蒙共享库使用 Kuikly KBA 工具链及其 OHOS 专用协程、序列化 KLib；这些依赖只存在于
`harmony-kmp` 子构建，不加入标准 Kotlin 2.1 Maven BOM，避免污染 Android/iOS 元数据。
发布 HAR 会在 `libs/arm64-v8a` 中携带 release 版 `libshared.so`，宿主不再单独复制该 SO。

产出命令、接入方式、调用链和性能影响见 [`../docs/harmony-har-release.md`](../docs/harmony-har-release.md)。

当前 Bridge 只支持 JSON 文本请求。二进制、流式传输和证书 Pin 需要直接实现鸿蒙专用
`NetworkEngine`；没有实现时会明确拒绝，而不是静默降级。
