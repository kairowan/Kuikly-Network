# 应用初始化与多 Base URL

推荐把 Client 当作应用级基础设施：宿主启动时创建一次，业务类按服务名称取得，而不是让页面重复持有 Base URL
和拦截器配置。

## 注册默认与命名 Client

```kotlin
object AppNetwork {
    fun initialize(session: Session, config: AppConfig) {
        NetworkClients.initialize(
            defaultClient = createNetworkClient(config.apiBaseUrl) {
                commonHeaders { session.headers() }
                bearerAuthentication(session::accessToken, session::refresh)
                responseCache(store = config.cacheStore)
            },
            namedClients = mapOf(
                "upload" to createNetworkClient(config.uploadBaseUrl) {
                    commonHeaders { session.headers() }
                    defaultTimeout(120)
                },
                "analytics" to createNetworkClient(config.analyticsBaseUrl) {
                    addInterceptor(config.analyticsSigner)
                    noUserCredentials()
                },
            ),
        )
    }
}
```

示例中的 `noUserCredentials()` 是宿主可以自行提供的 Builder 扩展；它不是库内 API。不同 Client 可以有完全
不同的拦截器、默认超时、缓存和 TLS 策略。

客户端名称必须以 ASCII 字母开头，只能包含字母、数字、`-_.`，长度不超过 64；`default` 是保留名称。

## Service 绑定 Client

```kotlin
@NetworkService
interface UserApi

@NetworkService(client = "upload")
interface UploadApi

@NetworkService(client = "analytics")
interface AnalyticsApi
```

```kotlin
val users = createUserApi()          // default
val upload = createUploadApi()       // upload
val events = createAnalyticsApi()    // analytics
```

需要临时覆盖时，传入运行时名称：

```kotlin
val stagingUpload = createUploadApi(clientName = "staging")
```

或者直接读取 Client：

```kotlin
val uploadClient = NetworkClients.client("upload")
```

## Android Application

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppNetwork.initialize(session, appConfig)
    }
}
```

```xml
<application android:name=".App" />
```

## iOS App / AppDelegate

公共初始化对象会导出给 Swift：

```swift
@main
struct iOSApp: App {
    init() {
        SampleNetworkRuntime.shared.initialize(
            defaultBaseUrl: "https://api.example.com",
            sampleBaseUrl: "https://upload.example.com"
        )
    }
}
```

真实项目通常把环境地址和会话对象从 iOS 配置层传给公共初始化函数，不应把生产密钥写入源码。

## 生命周期选择

| 请求归属 | Client/Scope | 离开页面 |
| --- | --- | --- |
| Repository、同步、上传任务 | 应用级 `NetworkClients` + Application Scope | 继续执行 |
| 只对当前页面有意义的请求 | 页面协程或 Kuikly `networkScope` | 自动取消 |
| 测试 | 显式 `ScriptedNetworkEngine.client()` | 测试自行清理 |

全局 Client 不应注册到 Kuikly Pager 的 Client 列表，否则页面销毁仍会调用它的 `cancelAll()`。

!!! warning "HarmonyOS 当前限制"

    内置 Harmony Bridge 依赖活动的 Kuikly Pager。公共注册表和命名 Service 可以编译到 HAR，但如果请求必须在
    页面销毁后继续执行，宿主需要提供真正的应用级 Harmony `NetworkEngine`，不能继续使用已销毁页面的 Bridge。

## 环境切换

`initialize` 有意只允许一次。开发环境切换应在进程重启前决定 Base URL；不要在请求进行中替换 Client。测试可以：

```kotlin
@AfterTest
fun cleanup() {
    NetworkClients.shutdown()
}
```

`shutdown()` 会去重后取消全部已注册 Client，再清空注册表。
