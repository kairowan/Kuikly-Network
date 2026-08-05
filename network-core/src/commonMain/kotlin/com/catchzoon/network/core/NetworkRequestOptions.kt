package com.catchzoon.network.core

/** 单次请求策略；业务可覆盖超时、响应上限和重试规则。 */
public data class NetworkRequestOptions(
    val headers: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int? = null,
    val maxResponseBytes: Long? = null,
    val retryPolicy: NetworkRetryPolicy? = null,
    val cachePolicy: NetworkCachePolicy? = null,
    val requestBody: NetworkRequestBody? = null,
    val priority: NetworkPriority? = null,
    val tags: Set<String> = emptySet(),
    val redirectPolicy: NetworkRedirectPolicy? = null,
    val progressListener: NetworkProgressListener? = null,
    val allowAbsoluteUrl: Boolean = false,
    val streamResponse: Boolean = false,
    val cacheTags: Set<String> = emptySet(),
    val invalidateCacheTags: Set<String> = emptySet(),
    val retryBudget: NetworkRetryBudget? = null,
) {
    init {
        require(timeoutSeconds == null || timeoutSeconds in 1..300) { "timeoutSeconds 必须在 1..300 之间" }
        require(maxResponseBytes == null || maxResponseBytes in 1..MAX_RESPONSE_BYTES) {
            "maxResponseBytes 超出允许范围"
        }
        require((tags + cacheTags + invalidateCacheTags).all(String::isValidNetworkTag)) { "网络标签无效" }
    }
}

/** 客户端默认请求策略；接口注解和单次调用链配置拥有更高优先级。 */
public data class NetworkClientDefaults(
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    val retryPolicy: NetworkRetryPolicy = NetworkRetryPolicy.none(),
    val cachePolicy: NetworkCachePolicy = NetworkCachePolicy.none(),
    val priority: NetworkPriority = NetworkPriority.NORMAL,
    val redirectPolicy: NetworkRedirectPolicy = NetworkRedirectPolicy(),
    val retryBudget: NetworkRetryBudget? = null,
) {
    init {
        require(timeoutSeconds in 1..300) { "timeoutSeconds 必须在 1..300 之间" }
        require(maxResponseBytes in 1..MAX_RESPONSE_BYTES) { "maxResponseBytes 超出允许范围" }
    }
}

private const val MAX_RESPONSE_BYTES = 20L * 1024L * 1024L
private const val DEFAULT_TIMEOUT_SECONDS = 30
private const val DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024L * 1024L

private fun String.isValidNetworkTag(): Boolean =
    length in 1..64 && all { it.isLetterOrDigit() || it in "-_.:/" }
