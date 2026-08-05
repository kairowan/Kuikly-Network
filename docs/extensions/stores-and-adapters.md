# 缓存、Cookie 与 Call Adapter

## 自定义缓存 Store

```kotlin
class EncryptedCacheStore(
    private val database: CacheDatabase,
    private val crypto: Crypto,
) : NetworkPersistentCacheStore {
    override val encryptedAtRest: Boolean = true

    override suspend fun get(key: String): NetworkCacheEntry? =
        database.read(hashAgain(key))?.let { crypto.decode(it) }

    override suspend fun put(key: String, entry: NetworkCacheEntry) {
        database.write(hashAgain(key), crypto.encode(entry))
    }

    override suspend fun remove(key: String) {
        database.remove(hashAgain(key))
    }

    override suspend fun removeByTags(tags: Set<String>) {
        database.removeByTags(tags)
    }

    override suspend fun clear() {
        database.clear()
    }
}
```

示例数据库、序列化和加密类型由宿主提供。持久化 Store 应：

- 再次散列传入键，不落盘可关联的原始身份信息。
- 加密静态数据并安全管理密钥。
- 为容量、过期记录和标签索引设置上限。
- 让 `removeByTags` 精确失效，而不是每次清空全部缓存。
- 传播协程取消；普通 I/O 异常可由缓存 Interceptor 安全旁路。

## 自定义 Cookie Store

```kotlin
class SecureCookieStore : NetworkCookieStore {
    override suspend fun load(request: NetworkRawRequest): List<NetworkCookie> =
        keychain.readCookies().filter { request.relativePath.startsWith(it.path) }

    override suspend fun save(
        request: NetworkRawRequest,
        response: NetworkRawResponse,
    ) {
        // 解析/保存 Set-Cookie，并执行容量和过期清理
    }

    override suspend fun clear() {
        keychain.clearCookies()
    }
}
```

```kotlin
val client = createNetworkClient(baseUrl) {
    cookies(SecureCookieStore())
}
```

身份 Cookie 属于敏感凭据，生产 Store 应使用 Keychain/Keystore 或等价安全存储。显式 `Cookie` Header 优先于 Store。

## Connectivity Provider

```kotlin
class AppConnectivityProvider(
    override val state: StateFlow<NetworkConnectivity>,
) : ObservableNetworkConnectivityProvider
```

```kotlin
val client = createNetworkClient(baseUrl) {
    connectivity(appConnectivity)
}
```

Provider 只描述当前快照；Android ConnectivityManager、iOS NWPathMonitor 与 Harmony 网络监听由宿主适配。

## Call Adapter

`NetworkCallAdapter` 让 Core 不必直接依赖 RxJava、Promise 或公司任务框架：

```kotlin
class DeferredCallAdapter<T>(
    private val scope: CoroutineScope,
) : NetworkCallAdapter<T, Deferred<NetworkResult<T>>> {
    override fun adapt(call: NetworkCall<T>): Deferred<NetworkResult<T>> =
        scope.async { call.await() }
}
```

```kotlin
val deferred = api.detail(id).adapt(DeferredCallAdapter(scope))
```

Adapter 应把上层取消传回协程/Call，不能创建无法停止的全局任务。

## Retry Budget

简单项目直接使用 `TokenBucketRetryBudget`。如果公司已有全局预算系统，只实现：

```kotlin
fun interface NetworkRetryBudget {
    suspend fun tryAcquire(): Boolean
}
```

`true` 表示允许一次额外重试，`false` 立即保留本次失败。不要让预算实现内部再次发起同一请求。

## Event 与进度 Adapter

事件监听和进度监听都是旁路扩展：

```kotlin
val events = NetworkEventListener(telemetry::onNetworkEvent)
val progress = NetworkProgressListener(uploadUi::render)
```

事件不携带正文；进度线程由 Engine 决定。监听器应快速、脱敏，并把重工作异步交给自己的有界队列。
