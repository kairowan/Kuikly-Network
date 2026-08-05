# Demo 运行指南

仓库包含三个宿主示例，目标是验证同一个 Kuikly 页面和网络库产物能在 Android、iOS 与 HarmonyOS 中工作。

## 示例入口

| 平台 | 工程 | 入口/页面 |
| --- | --- | --- |
| Android | `androidApp` | `MainActivity` → `network_sample` |
| iOS | `iosApp` | `ContentView` → `network_sample` |
| HarmonyOS | `ohosApp` | `EntryAbility` → Kuikly 示例页 |

## Android

```bash
./gradlew :androidApp:installDebug
```

设备上打开应用后进入网络示例页，依次验证普通请求、错误结果和页面跳转后的生命周期行为。没有设备时先执行 `:androidApp:assembleDebug` 验证产物。

## iOS

```bash
./gradlew :shared:generateDummyFramework
cd iosApp && pod install
open iosApp.xcworkspace
```

选择模拟器后运行 `iosApp` Scheme。若使用真机，需要在 Xcode 中配置开发团队和签名。

## HarmonyOS

先生成 HAR，再生成并安装消费该 HAR 的 HAP：

```bash
DEVECO_STUDIO_HOME=/Applications/DevEco-Studio.app ./scripts/build_harmony.sh har
DEVECO_STUDIO_HOME=/Applications/DevEco-Studio.app ./scripts/build_harmony.sh hap
```

DevEco Studio 自动签名完成后，可直接选择 Pura 70 运行；也可使用 `hdc app install -r` 安装 signed HAP。完整命令见 [HarmonyOS / OpenHarmony](../platforms/harmony.md)。

## 验收建议

- 应用能进入示例页，没有初始化异常；
- 点击请求按钮后能得到成功或可解释的业务错误，而不是崩溃；
- Android/iOS 的应用级任务离开页面后仍可继续；
- 页面级请求在页面释放后被取消；
- HarmonyOS 示例 HAP 使用本次生成的 HAR；
- 自定义 Header、拦截器和命名 Base URL 在三个宿主的公共代码中保持一致。

!!! note "示例服务"
    Demo 的远端服务可能受网络、证书或服务状态影响。验证库逻辑时优先用 `ScriptedNetworkEngine`，把真实服务连通性与 SDK 行为分开排查。

