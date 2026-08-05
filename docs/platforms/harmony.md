# HarmonyOS / OpenHarmony

仓库提供 HarmonyOS HAR 产物和使用该 HAR 的示例 HAP。构建脚本会先生成 HAR，再让示例应用以本地产物方式消费它，从而验证发布产物而不是源码偶然可用。

## 环境要求

- DevEco Studio 已安装；
- OpenHarmony SDK、Node.js 与 `hvigorw` 可用；
- 真机调试时已在 DevEco Studio 中完成自动签名；
- 设备已开启开发者模式并允许 USB 调试。

DevEco Studio 不在默认位置时，通过环境变量指定：

```bash
export DEVECO_STUDIO_HOME=/Applications/DevEco-Studio.app
```

## 构建 HAR

```bash
DEVECO_STUDIO_HOME=/Applications/DevEco-Studio.app \
  ./scripts/build_harmony.sh har
```

产物位于：

```text
network-ohos/build/default/outputs/default/network_ohos.har
```

## 构建示例 HAP

```bash
DEVECO_STUDIO_HOME=/Applications/DevEco-Studio.app \
  ./scripts/build_harmony.sh hap
```

脚本会校验示例工程确实依赖刚生成的 HAR。签名成功后的产物通常位于：

```text
ohosApp/entry/build/default/outputs/default/entry-default-signed.hap
```

## 安装到真机

确认设备在线：

```bash
/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc list targets
```

安装并启动：

```bash
/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc \
  app install -r \
  ohosApp/entry/build/default/outputs/default/entry-default-signed.hap

/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc \
  shell aa start \
  -a EntryAbility \
  -b com.catchzoon.network.sample.ohos
```

也可以在 DevEco Studio 中打开 `ohosApp`，允许自动签名后选择 Pura 70 运行。

## 当前生命周期边界

Android 和 iOS 可以在应用入口安装独立于页面的 `NetworkClients`。HarmonyOS 当前内置 Bridge 仍依赖活动中的 Kuikly `Pager` 来取得引擎，因此：

- 页面内请求可正常使用并随页面作用域取消；
- HAR 引入、HAP 打包、安装和页面能力可以独立验证；
- 要让请求离开页面继续执行，需要宿主提供不依赖 `Pager` 的 HarmonyOS `NetworkEngine`，再交给应用级作用域执行。

这不是 `NetworkCall` 的限制，而是当前 HarmonyOS 引擎的宿主边界。自定义引擎的接口与实现方式见[自定义网络引擎](../extensions/engine.md)。

## 常见安装问题

| 现象 | 检查项 |
| --- | --- |
| `hdc list targets` 为空 | USB 模式、开发者模式、授权弹窗、数据线 |
| 找不到 signed HAP | DevEco 自动签名是否启用，签名 Profile 是否匹配设备 |
| HAP 编译成功但运行白屏 | 查看 DevEco HiLog，确认 `EntryAbility` 与 Kuikly 页面注册 |
| 改过 HAR 但应用行为未变化 | 重新执行 `build_harmony.sh hap`，不要复用旧 HAP |
| 外置磁盘出现 `._` 文件 | 见[常见问题](../guides/faq.md)中的 AppleDouble 说明 |
