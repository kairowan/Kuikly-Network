package com.catchzoon.network.core

/**
 * 应用级网络客户端注册表。
 *
 * 宿主应在 Application、AppDelegate 等应用入口初始化一次；业务代码和 KSP 生成的 Service
 * 随后可以按名称直接取得客户端，不再逐层传递 [NetworkClient]。
 */
public object NetworkClients {
    public const val DEFAULT: String = "default"

    private var registered: Map<String, NetworkClient>? = null

    /** 是否已经完成应用级初始化。 */
    public val isInitialized: Boolean
        get() = registered != null

    /** 当前已经注册的客户端名称；尚未初始化时返回空集合。 */
    public val names: Set<String>
        get() = registered?.keys.orEmpty()

    /**
     * 注册默认客户端以及使用其他 Base URL 的命名客户端。
     *
     * 初始化只允许执行一次，避免运行期间悄悄替换仍有请求在途的客户端。
     */
    public fun initialize(
        defaultClient: NetworkClient,
        namedClients: Map<String, NetworkClient> = emptyMap(),
    ) {
        check(registered == null) { "NetworkClients 已经初始化" }
        require(DEFAULT !in namedClients) { "命名客户端不能使用保留名称：$DEFAULT" }
        require(namedClients.keys.all(String::isValidNetworkClientName)) { "网络客户端名称无效" }
        // ponytail: 应用启动阶段单线程初始化；需要运行中切换环境时再引入跨平台原子快照。
        registered = buildMap {
            put(DEFAULT, defaultClient)
            putAll(namedClients)
        }
    }

    /** 取得默认客户端，或取得使用其他 Base URL 的命名客户端。 */
    public fun client(name: String = DEFAULT): NetworkClient {
        require(name.isValidNetworkClientName()) { "网络客户端名称无效：$name" }
        val clients = checkNotNull(registered) { "NetworkClients 尚未初始化，请先在应用入口调用 initialize" }
        return checkNotNull(clients[name]) { "未注册网络客户端：$name；可用客户端：${clients.keys.joinToString()}" }
    }

    /** 取消全部应用级请求并清空注册表；主要用于应用退出或测试清理。 */
    public fun shutdown() {
        registered?.values?.toSet()?.forEach(NetworkClient::cancelAll)
        registered = null
    }
}

private val networkClientName = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")

private fun String.isValidNetworkClientName(): Boolean = networkClientName.matches(this)
