package com.catchzoon.network.kuikly

import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkTlsPolicy
import com.catchzoon.network.core.NetworkTransportException
import com.catchzoon.network.core.isSafeAbsoluteNetworkUrl
import com.catchzoon.network.core.isSafeRelativeNetworkPath
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 按目标平台创建 Kuikly 页面网络引擎；Android/iOS 使用原生引擎，鸿蒙使用 Kuikly Bridge。 */
internal expect fun createKuiklyScopedNetworkEngine(
    pager: Pager,
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy,
): NetworkEngine

/**
 * 使用 Kuikly `KRNetworkModule` 的跨端桥接引擎。
 *
 * 该引擎供没有公共 Kotlin 原生 HTTP 实现的平台使用；正文仅支持 JSON 文本，二进制传输应由目标平台
 * 单独实现 [NetworkEngine]。
 */
internal class KuiklyBridgeNetworkEngine(
    pager: Pager,
    private val baseUrl: String,
) : NetworkEngine {
    private val module by lazy { pager.acquireModule<KuiklyNetworkModule>(KuiklyNetworkModule().moduleName()) }
    private var closed = false

    init {
        require(baseUrl.startsWith("https://", ignoreCase = true)) { "Kuikly Bridge 只允许 HTTPS" }
    }

    override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse =
        suspendCancellableCoroutine { continuation ->
            if (closed) {
                continuation.resumeWithException(cancelledFailure())
                return@suspendCancellableCoroutine
            }
            if (request.bodyBytes != null) {
                continuation.resumeWithException(unsupportedBinaryFailure())
                return@suspendCancellableCoroutine
            }
            val url = resolveBridgeUrl(baseUrl, request) ?: run {
                continuation.resumeWithException(invalidUrlFailure())
                return@suspendCancellableCoroutine
            }
            module.request(
                url = url,
                method = request.method,
                body = request.body,
                headers = request.headers,
                timeoutSeconds = request.timeoutSeconds,
                maxResponseBytes = request.maxResponseBytes,
            ) callback@{ data, success, error, response ->
                if (!continuation.isActive || closed) return@callback
                if (data.encodeToByteArray().size > request.maxResponseBytes) {
                    continuation.resumeWithException(responseTooLargeFailure(request.maxResponseBytes))
                    return@callback
                }
                val statusCode = response.statusCode ?: if (success) 200 else 0
                if (!success && statusCode == 0) {
                    continuation.resumeWithException(transportFailure(error))
                } else {
                    continuation.resume(
                        NetworkRawResponse(
                            statusCode = statusCode,
                            body = data,
                            headers = response.headerFields.keySet().associateWith(response.headerFields::optString),
                            errorMessage = error,
                        ),
                    )
                }
            }
        }

    override fun cancelAll() {
        closed = true
        module.cancelAll()
    }
}

private fun resolveBridgeUrl(baseUrl: String, request: NetworkRawRequest): String? = when {
    request.allowAbsoluteUrl && isSafeAbsoluteNetworkUrl(request.relativePath) -> request.relativePath
    isSafeRelativeNetworkPath(request.relativePath) -> baseUrl.trimEnd('/') + request.relativePath
    else -> null
}

private fun cancelledFailure(): NetworkTransportException = NetworkTransportException(
    NetworkFailure(
        code = "request_cancelled",
        category = NetworkFailureCategory.CANCELLED,
        message = "请求已取消",
    ),
)

private fun unsupportedBinaryFailure(): NetworkTransportException = NetworkTransportException(
    NetworkFailure(
        code = "unsupported_binary_body",
        category = NetworkFailureCategory.VALIDATION,
        message = "当前 Kuikly Bridge 不支持二进制请求正文",
    ),
)

private fun invalidUrlFailure(): NetworkTransportException = NetworkTransportException(
    NetworkFailure(code = "invalid_request", category = NetworkFailureCategory.VALIDATION, message = "接口地址无效"),
)

private fun responseTooLargeFailure(maxResponseBytes: Long): NetworkTransportException = NetworkTransportException(
    NetworkFailure(
        code = "response_too_large",
        category = NetworkFailureCategory.RESPONSE_TOO_LARGE,
        message = "响应正文超过 ${maxResponseBytes}B 限制",
    ),
)

private fun transportFailure(message: String): NetworkTransportException = NetworkTransportException(
    NetworkFailure(
        code = "network_unavailable",
        category = NetworkFailureCategory.CONNECTIVITY,
        message = message.ifBlank { "网络连接不可用" },
        retryable = true,
    ),
)
