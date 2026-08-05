package com.catchzoon.network.core

/** 响应实际来源，业务可区分在线数据、缓存数据和本地降级数据。 */
public enum class NetworkResponseSource { NETWORK, MEMORY_CACHE, STALE_CACHE, LOCAL_FALLBACK }

/** 平台传输层返回的原始响应，数据解析统一留给公共层。 */
public data class NetworkRawResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val errorMessage: String = "",
    val durationMillis: Long = 0L,
    val source: NetworkResponseSource = NetworkResponseSource.NETWORK,
    val bodyBytes: ByteArray? = null,
    val headerValues: Map<String, List<String>> = headers.mapValues { (_, value) -> listOf(value) },
)
