package com.catchzoon.network.core

/** 公共网络模块支持的 HTTP 方法。 */
public enum class NetworkMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }

/** 请求调度优先级；平台引擎和并发控制器可以据此优先处理用户可见请求。 */
public enum class NetworkPriority { LOW, NORMAL, HIGH, IMMEDIATE }

/** 单次传输进度。回调所在线程由平台引擎决定，调用方只应执行轻量操作。 */
public data class NetworkTransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val direction: NetworkTransferDirection,
)

public enum class NetworkTransferDirection { UPLOAD, DOWNLOAD }

public fun interface NetworkProgressListener {
    public fun onProgress(progress: NetworkTransferProgress)
}

/** 重定向策略；默认只允许同源跳转，防止鉴权头被转发到第三方域名。 */
public data class NetworkRedirectPolicy(
    val enabled: Boolean = true,
    val maxRedirects: Int = 5,
    val allowCrossOrigin: Boolean = false,
) {
    init {
        require(maxRedirects in 0..10) { "maxRedirects 必须在 0..10 之间" }
    }
}

/** 平台传输层接收的不可变原始请求。 */
public data class NetworkRawRequest(
    val relativePath: String,
    val method: NetworkMethod,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    val cachePolicy: NetworkCachePolicy = NetworkCachePolicy.none(),
    val bodyBytes: ByteArray? = null,
    val priority: NetworkPriority = NetworkPriority.NORMAL,
    val tags: Set<String> = emptySet(),
    val redirectPolicy: NetworkRedirectPolicy = NetworkRedirectPolicy(),
    val progressListener: NetworkProgressListener? = null,
    val allowAbsoluteUrl: Boolean = false,
    val streamResponse: Boolean = false,
    val cacheTags: Set<String> = emptySet(),
    val invalidateCacheTags: Set<String> = emptySet(),
)

private const val DEFAULT_TIMEOUT_SECONDS = 30
private const val DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
