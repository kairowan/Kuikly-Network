# Android 与 iOS

Android 和 iOS 共用 `network-core`、生成代码和大部分业务逻辑；平台模块只负责提供网络引擎、生命周期入口与少量平台能力。

## Android 初始化

建议在 `Application.onCreate()` 中完成默认客户端与命名客户端注册：

```kotlin
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        NetworkClients.initialize(
            defaultClient = createNetworkClient("https://api.example.com/"),
            namedClients = mapOf(
                "upload" to createNetworkClient("https://upload.example.com/"),
            ),
        )
    }
}
```

随后任意业务类都可以直接使用 KSP 生成的无参工厂：

```kotlin
private val userApi = createUserApi()
private val uploadApi = createUploadApi() // @NetworkService(client = "upload")
```

如果应用主动退出登录或需要整体释放网络资源，调用：

```kotlin
NetworkClients.shutdown()
```

## iOS 初始化

在 SwiftUI 应用入口或宿主启动流程中注册一次：

```swift
import SwiftUI
import shared

@main
struct IOSApp: App {
    init() {
        SampleNetworkRuntime.shared.initialize(
            defaultBaseUrl: "https://api.example.com/",
            sampleBaseUrl: "https://upload.example.com/"
        )
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

`SampleNetworkRuntime` 放在共享 Kotlin 代码中，内部同样使用 `NetworkClients.initialize(...)`。这样 Swift 只负责触发初始化，不需要理解客户端构建细节。

## 明文 HTTP 与 TLS

生产环境建议显式禁止明文请求：

```kotlin
createNetworkClient(
    baseUrl = "https://api.example.com/",
    tlsPolicy = NetworkTlsPolicy(allowCleartext = false),
)
```

Android 还应同步检查 `networkSecurityConfig`；iOS 应遵守 App Transport Security。不要仅依靠业务拦截器阻止不安全地址。

## 编译 Android Demo

```bash
./gradlew :androidApp:assembleDebug
```

连接设备后可以直接安装：

```bash
./gradlew :androidApp:installDebug
```

也可以用 Android Studio 打开仓库，选择 `androidApp` 配置运行。Demo 会进入 `network_sample` 页面。

## 编译 iOS Demo

首次运行先生成共享 Framework 并安装 Pods：

```bash
./gradlew :shared:generateDummyFramework
cd iosApp
pod install
```

随后必须打开 Workspace：

```bash
open iosApp.xcworkspace
```

在 Xcode 中选择 `iosApp` Scheme 和模拟器或真机运行。命令行无签名校验可使用：

```bash
xcodebuild \
  -workspace iosApp/iosApp.xcworkspace \
  -scheme iosApp \
  -sdk iphonesimulator \
  -configuration Debug \
  CODE_SIGNING_ALLOWED=NO \
  ARCHS=arm64 \
  ONLY_ACTIVE_ARCH=YES \
  build
```

!!! warning "不要打开 `.xcodeproj`"
    CocoaPods 依赖只会被 `.xcworkspace` 正确装配；直接打开 `.xcodeproj` 常见结果是找不到共享 Framework 或 Pods 模块。
