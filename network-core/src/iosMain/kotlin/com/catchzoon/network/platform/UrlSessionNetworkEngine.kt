@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.catchzoon.network.platform

import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkProgressListener
import com.catchzoon.network.core.NetworkRedirectPolicy
import com.catchzoon.network.core.NetworkTransportException
import com.catchzoon.network.core.NetworkTransferDirection
import com.catchzoon.network.core.NetworkTransferProgress
import com.catchzoon.network.core.NetworkTlsPolicy
import com.catchzoon.network.core.isSafeRelativeNetworkPath
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.memScoped
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorClientCertificateRejected
import platform.Foundation.NSURLErrorClientCertificateRequired
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionTask
import platform.Foundation.dataWithBytes
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustGetCertificateCount
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS 原生 NSURLSession 引擎。
 *
 * 不经过只接受 JSONObject 的 Kuikly NetworkModule，因此支持完整 JSON 正文、全部 HTTP 方法和真实协程取消。
 */
public class UrlSessionNetworkEngine(
    private val baseUrl: String,
    tlsPolicy: NetworkTlsPolicy = NetworkTlsPolicy(allowCleartext = true),
) : NetworkEngine {
    private val delegate = NetworkSessionDelegate(tlsPolicy)
    private val session = NSURLSession.sessionWithConfiguration(
        configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration,
        delegate = delegate,
        delegateQueue = null,
    )

    init {
        tlsPolicy.validateBaseUrl(baseUrl)
    }

    override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse =
        suspendCancellableCoroutine { continuation ->
            val url = resolveNetworkUrl(baseUrl, request.relativePath)
            if (url == null) {
                continuation.resumeWithException(IllegalArgumentException("接口路径无效"))
                return@suspendCancellableCoroutine
            }
            val nativeUrl = platform.Foundation.NSURL.URLWithString(url)
            if (nativeUrl == null) {
                continuation.resumeWithException(IllegalArgumentException("接口地址无效"))
                return@suspendCancellableCoroutine
            }
            val nativeRequest = NSMutableURLRequest.requestWithURL(nativeUrl)
            nativeRequest.setHTTPMethod(request.method.name)
            nativeRequest.setTimeoutInterval(request.timeoutSeconds.toDouble())
            request.headers.forEach { (name, value) -> nativeRequest.setValue(value, forHTTPHeaderField = name) }
            val bodyBytes = request.bodyBytes ?: request.body.encodeToByteArray()
            if (bodyBytes.isNotEmpty()) {
                if (request.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    nativeRequest.setValue(JSON_CONTENT_TYPE, forHTTPHeaderField = "Content-Type")
                }
                nativeRequest.setHTTPBody(bodyBytes.toNSData())
            }
            val task = session.dataTaskWithRequest(nativeRequest)
            delegate.register(
                task = task,
                maxResponseBytes = request.maxResponseBytes,
                continuation = continuation,
                progressListener = request.progressListener,
                redirectPolicy = request.redirectPolicy,
            )
            continuation.invokeOnCancellation {
                delegate.cancel(task)
            }
            request.progressListener.notifyProgress(
                transferred = bodyBytes.size.toLong(),
                total = bodyBytes.size.toLong(),
                direction = NetworkTransferDirection.UPLOAD,
            )
            task.resume()
        }

    /** 取消当前客户端的全部在途请求；后续创建的新请求仍然可用。 */
    override fun cancelAll(): Unit = delegate.cancelAll()
}

public actual fun createPlatformNetworkEngine(
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy,
): NetworkEngine = UrlSessionNetworkEngine(baseUrl, tlsPolicy)

public actual fun resolveNetworkUrl(baseUrl: String, relativePath: String): String? {
    if (com.catchzoon.network.core.isSafeAbsoluteNetworkUrl(relativePath)) return relativePath
    if (!isSafeRelativeNetworkPath(relativePath)) return null
    val components = NSURLComponents(string = baseUrl)
    val basePath = components.percentEncodedPath?.trimEnd('/').orEmpty()
    val relativePathValue = relativePath.substringBefore('?').trimStart('/')
    components.percentEncodedPath = "$basePath/$relativePathValue"
    components.percentEncodedQuery = relativePath.substringAfter('?', "").ifEmpty { null }
    return components.URL?.absoluteString
}

private inline fun <T> NSLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}

private class NetworkSessionDelegate(
    private val tlsPolicy: NetworkTlsPolicy,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val lock = NSLock()
    private val pending = mutableMapOf<ULong, PendingRequest>()

    fun register(
        task: NSURLSessionDataTask,
        maxResponseBytes: Long,
        continuation: CancellableContinuation<NetworkRawResponse>,
        progressListener: NetworkProgressListener?,
        redirectPolicy: NetworkRedirectPolicy,
    ) {
        lock.withLock {
            pending[task.taskIdentifier] = PendingRequest(
                task,
                maxResponseBytes,
                continuation,
                progressListener,
                redirectPolicy,
            )
        }
    }

    fun cancel(task: NSURLSessionDataTask) {
        lock.withLock { pending.remove(task.taskIdentifier) }
        task.cancel()
    }

    fun cancelAll() {
        val tasks = lock.withLock { pending.values.map(PendingRequest::task) }
        tasks.forEach(NSURLSessionDataTask::cancel)
    }

    override fun URLSession(
        session: NSURLSession,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (Long, NSURLCredential?) -> Unit,
    ) {
        val protectionSpace = didReceiveChallenge.protectionSpace
        if (protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }
        val pins = tlsPolicy.pinsForHost(protectionSpace.host)
        if (pins.isEmpty()) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }
        val trust = protectionSpace.serverTrust
        if (trust == null || !SecTrustEvaluateWithError(trust, null)) {
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            return
        }
        val matches = (0 until SecTrustGetCertificateCount(trust)).any { index ->
            SecTrustGetCertificateAtIndex(trust, index)?.let(::certificateSha256Pin) in pins
        }
        if (matches) {
            completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
        } else {
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (Long) -> Unit,
    ) {
        lock.withLock { pending[dataTask.taskIdentifier]?.response = didReceiveResponse as? NSHTTPURLResponse }
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        var rejected: Pair<PendingRequest, String>? = null
        var acceptedRequest: NSURLRequest? = newRequest
        lock.withLock {
            val request = pending[task.taskIdentifier] ?: return@withLock
            val policy = request.redirectPolicy
            val sourceUrl = task.currentRequest?.URL
            val targetUrl = newRequest.URL
            val crossOrigin = sourceUrl == null || targetUrl == null ||
                sourceUrl.scheme != targetUrl.scheme || sourceUrl.host != targetUrl.host || sourceUrl.port != targetUrl.port
            val rejection = when {
                !policy.enabled -> "当前请求已禁止重定向"
                request.redirectCount >= policy.maxRedirects -> "重定向次数超过限制"
                crossOrigin && !policy.allowCrossOrigin -> "已阻止跨域重定向"
                else -> null
            }
            if (rejection != null) {
                pending.remove(task.taskIdentifier)
                acceptedRequest = null
                rejected = request to rejection
            } else {
                request.redirectCount++
                if (crossOrigin) {
                    acceptedRequest = (newRequest.mutableCopy() as? NSMutableURLRequest)?.apply {
                        CROSS_ORIGIN_SECRET_HEADERS.forEach { setValue(null, forHTTPHeaderField = it) }
                    } ?: newRequest
                }
            }
        }
        completionHandler(acceptedRequest)
        rejected?.let { (request, message) ->
            task.cancel()
            if (request.continuation.isActive) {
                request.continuation.resumeWithException(redirectFailure(message))
            }
        }
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        var overflow: PendingRequest? = null
        lock.withLock {
            val request = pending[dataTask.taskIdentifier] ?: return@withLock
            val bytes = didReceiveData.toByteArray()
            if (request.receivedBytes + bytes.size > request.maxResponseBytes) {
                overflow = pending.remove(dataTask.taskIdentifier)
            } else {
                request.receivedBytes += bytes.size
                request.chunks += bytes
                request.progressListener.notifyProgress(
                    transferred = request.receivedBytes,
                    total = request.response?.expectedContentLength?.takeIf { it >= 0L },
                    direction = NetworkTransferDirection.DOWNLOAD,
                )
            }
        }
        overflow?.let { request ->
            dataTask.cancel()
            if (request.continuation.isActive) {
                request.continuation.resumeWithException(responseTooLarge(request.maxResponseBytes))
            }
        }
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: platform.Foundation.NSError?) {
        val request = lock.withLock { pending.remove(task.taskIdentifier) } ?: return
        if (!request.continuation.isActive) return
        if (didCompleteWithError != null) {
            request.continuation.resumeWithException(didCompleteWithError.toTransportException())
            return
        }
        val body = ByteArray(request.receivedBytes.toInt())
        var destinationOffset = 0
        request.chunks.forEach { chunk ->
            chunk.copyInto(body, destinationOffset)
            destinationOffset += chunk.size
        }
        request.continuation.resume(
            NetworkRawResponse(
                statusCode = request.response?.statusCode?.toInt() ?: 0,
                body = body.decodeToString(),
                bodyBytes = body,
                headers = request.response?.allHeaderFields?.toStringMap().orEmpty(),
            ),
        )
    }
}

private class PendingRequest(
    val task: NSURLSessionDataTask,
    val maxResponseBytes: Long,
    val continuation: CancellableContinuation<NetworkRawResponse>,
    val progressListener: NetworkProgressListener?,
    val redirectPolicy: NetworkRedirectPolicy,
) {
    var response: NSHTTPURLResponse? = null
    var receivedBytes: Long = 0L
    val chunks = mutableListOf<ByteArray>()
    var redirectCount: Int = 0
}

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.dataWithBytes(bytes = pinned.addressOf(0), length = size.toULong())
}

private fun NetworkProgressListener?.notifyProgress(
    transferred: Long,
    total: Long?,
    direction: NetworkTransferDirection,
) {
    try {
        this?.onProgress(NetworkTransferProgress(transferred, total, direction))
    } catch (_: Exception) {
        // 进度监听是旁路能力，不能中断传输。
    }
}

private fun NSData.toByteArray(): ByteArray {
    if (length == 0UL) return ByteArray(0)
    return bytes?.reinterpret<ByteVar>()?.readBytes(length.toInt()) ?: ByteArray(0)
}

private fun certificateSha256Pin(certificate: SecCertificateRef): String? {
    val data = SecCertificateCopyData(certificate) ?: return null
    return try {
        val bytes = CFDataGetBytePtr(data) ?: return null
        val length = CFDataGetLength(data)
        memScoped {
            val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
            if (CC_SHA256(bytes, length.toUInt(), digest) == null) return@memScoped null
            "cert-sha256/" + (0 until CC_SHA256_DIGEST_LENGTH).joinToString(separator = "") { index ->
                digest[index].toString(16).padStart(2, '0')
            }
        }
    } finally {
        CFRelease(data)
    }
}

private fun Map<Any?, *>.toStringMap(): Map<String, String> = buildMap {
    this@toStringMap.forEach { (name, value) ->
        if (name != null && value != null) put(name.toString(), value.toString())
    }
}

private fun platform.Foundation.NSError.toTransportException(): NetworkTransportException {
    val urlErrorCode = code.takeIf { domain == NSURLErrorDomain }
    val timedOut = urlErrorCode == NSURLErrorTimedOut
    val cancelled = urlErrorCode == NSURLErrorCancelled
    val dnsFailure = urlErrorCode == NSURLErrorCannotFindHost || urlErrorCode == NSURLErrorDNSLookupFailed
    val tlsFailure = urlErrorCode in TLS_ERROR_CODES
    return NetworkTransportException(
        NetworkFailure(
            code = when {
                timedOut -> "request_timeout"
                cancelled -> "request_cancelled"
                dnsFailure -> "dns_failure"
                tlsFailure -> "tls_failure"
                else -> "network_unavailable"
            },
            category = when {
                timedOut -> NetworkFailureCategory.TIMEOUT
                cancelled -> NetworkFailureCategory.CANCELLED
                dnsFailure -> NetworkFailureCategory.DNS
                tlsFailure -> NetworkFailureCategory.TLS
                else -> NetworkFailureCategory.CONNECTIVITY
            },
            message = when {
                timedOut -> "请求超时"
                cancelled -> "请求已取消"
                dnsFailure -> "域名解析失败"
                tlsFailure -> "安全连接校验失败"
                else -> "网络连接不可用"
            },
            retryable = !cancelled && !tlsFailure,
        ),
    )
}

private fun responseTooLarge(maxResponseBytes: Long): NetworkTransportException = NetworkTransportException(
    NetworkFailure(
        code = "response_too_large",
        category = NetworkFailureCategory.RESPONSE_TOO_LARGE,
        message = "响应正文超过 ${maxResponseBytes}B 限制",
    ),
)

private fun redirectFailure(message: String): NetworkTransportException = NetworkTransportException(
    NetworkFailure(code = "redirect_rejected", category = NetworkFailureCategory.VALIDATION, message = message),
)

private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
private val CROSS_ORIGIN_SECRET_HEADERS = setOf("Authorization", "Proxy-Authorization", "Cookie")

private val TLS_ERROR_CODES = setOf(
    NSURLErrorSecureConnectionFailed,
    NSURLErrorServerCertificateHasBadDate,
    NSURLErrorServerCertificateUntrusted,
    NSURLErrorServerCertificateHasUnknownRoot,
    NSURLErrorServerCertificateNotYetValid,
    NSURLErrorClientCertificateRejected,
    NSURLErrorClientCertificateRequired,
)
