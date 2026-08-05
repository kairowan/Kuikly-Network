package com.catchzoon.network.platform

import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkTlsPolicy

/** Android 创建 Retrofit/OkHttp 引擎，iOS 创建 NSURLSession 引擎。 */
public expect fun createPlatformNetworkEngine(
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy,
): NetworkEngine

/** 由两个平台分别安全拼接基础地址和相对路径。 */
public expect fun resolveNetworkUrl(baseUrl: String, relativePath: String): String?

/** 一步创建当前平台客户端；需要鉴权或日志时可在 configure 中添加拦截器。 */
public fun createNetworkClient(
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy = NetworkTlsPolicy(allowCleartext = true),
    configure: NetworkClient.Builder.() -> Unit = {},
): NetworkClient = NetworkClient.Builder(createPlatformNetworkEngine(baseUrl, tlsPolicy))
    .apply(configure)
    .build()
