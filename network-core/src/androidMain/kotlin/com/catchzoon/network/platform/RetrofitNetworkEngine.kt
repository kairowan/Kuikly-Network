package com.catchzoon.network.platform

import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkMethod
import com.catchzoon.network.core.NetworkProgressListener
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkTransportException
import com.catchzoon.network.core.NetworkTransferDirection
import com.catchzoon.network.core.NetworkTransferProgress
import com.catchzoon.network.core.NetworkTlsPolicy
import com.catchzoon.network.core.isSafeRelativeNetworkPath
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Tag
import retrofit2.http.Url

/** Android 的公共 Retrofit/OkHttp 引擎，可传入定制 OkHttpClient 继续二次封装。 */
public class RetrofitNetworkEngine(
    baseUrl: String,
    okHttpClient: OkHttpClient = defaultOkHttpClient(),
    tlsPolicy: NetworkTlsPolicy = NetworkTlsPolicy(allowCleartext = true),
) : NetworkEngine {
    private val client = okHttpClient.newBuilder()
        .addInterceptor(PerRequestTimeoutInterceptor)
        .followRedirects(false)
        .followSslRedirects(false)
        .apply { configureCertificatePins(tlsPolicy) }
        .build()
    private val api = Retrofit.Builder()
        .baseUrl(baseUrl.toHttpUrl().newBuilder().addPathSegment("").build())
        .client(client)
        .build()
        .create(RetrofitTransportApi::class.java)

    init {
        tlsPolicy.validateBaseUrl(baseUrl)
    }

    override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
        try {
            var path = request.relativePath.toRetrofitUrl()
            var method = request.method
            var headers = request.headers
            var redirects = 0
            while (true) {
                val response = executeOnce(path, method, headers, request)
                val location = response.headers()["Location"]
                if (response.code() !in REDIRECT_STATUS_CODES || location.isNullOrBlank() ||
                    !request.redirectPolicy.enabled
                ) {
                    return response.toRawResponse(request)
                }
                response.errorBody()?.close()
                response.body()?.close()
                if (redirects >= request.redirectPolicy.maxRedirects) throw redirectFailure("重定向次数超过限制")
                val currentUrl = response.raw().request.url
                val target = currentUrl.resolve(location) ?: throw redirectFailure("重定向地址无效")
                val crossOrigin = currentUrl.scheme != target.scheme || currentUrl.host != target.host ||
                    currentUrl.port != target.port
                if (crossOrigin && !request.redirectPolicy.allowCrossOrigin) {
                    throw redirectFailure("已阻止跨域重定向")
                }
                if (crossOrigin) {
                    headers = headers.filterKeys { name ->
                        CROSS_ORIGIN_SECRET_HEADERS.none { it.equals(name, ignoreCase = true) }
                    }
                }
                if (response.code() in setOf(301, 302, 303) && method !in setOf(NetworkMethod.GET, NetworkMethod.HEAD)) {
                    method = NetworkMethod.GET
                }
                path = target.toString()
                redirects++
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            throw failure.toTransportException()
        }
    }

    override fun cancelAll(): Unit = client.dispatcher.cancelAll()

    private suspend fun executeOnce(
        path: String,
        method: NetworkMethod,
        headers: Map<String, String>,
        request: NetworkRawRequest,
    ): Response<ResponseBody> {
        val timeout = RequestTimeout(request.timeoutSeconds)
        val body = request.toRequestBody()
        return when (method) {
            NetworkMethod.GET -> api.get(path, headers, timeout)
            NetworkMethod.POST -> api.post(path, headers, body, timeout)
            NetworkMethod.PUT -> api.put(path, headers, body, timeout)
            NetworkMethod.PATCH -> api.patch(path, headers, body, timeout)
            NetworkMethod.DELETE -> api.delete(path, headers, body, timeout)
            NetworkMethod.HEAD -> api.head(path, headers, timeout)
            NetworkMethod.OPTIONS -> api.options(path, headers, body, timeout)
        }
    }

    private fun Response<ResponseBody>.toRawResponse(request: NetworkRawRequest): NetworkRawResponse {
        val payload = (if (isSuccessful) body() else errorBody()).readSafely(request)
        return NetworkRawResponse(
            statusCode = code(),
            body = payload.decodeToString(),
            bodyBytes = payload,
            headers = headers().toMultimap().mapValues { (_, values) -> values.joinToString(",") },
            headerValues = headers().toMultimap(),
        )
    }
}

private fun ResponseBody?.readSafely(request: NetworkRawRequest): ByteArray {
    if (this == null) return ByteArray(0)
    return use { responseBody ->
        val total = responseBody.contentLength().takeIf { it >= 0L }
        if (total != null && total > request.maxResponseBytes) throw responseTooLarge(request.maxResponseBytes)
        val source = responseBody.source()
        val output = Buffer()
        var received = 0L
        while (true) {
            val remaining = request.maxResponseBytes + 1L - received
            val read = source.read(output, minOf(PROGRESS_CHUNK_BYTES, remaining.coerceAtLeast(1L)))
            if (read < 0L) break
            received += read
            if (received > request.maxResponseBytes) throw responseTooLarge(request.maxResponseBytes)
            request.progressListener.notifyProgress(received, total, NetworkTransferDirection.DOWNLOAD)
        }
        output.readByteArray()
    }
}

private fun NetworkRawRequest.toRequestBody(): RequestBody {
    val bytes = bodyBytes ?: body.encodeToByteArray()
    val mediaType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value?.toMediaTypeOrNull() ?: JSON_MEDIA_TYPE
    val delegate = bytes.toRequestBody(mediaType)
    val listener = progressListener ?: return delegate
    return object : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(PROGRESS_CHUNK_BYTES.toInt(), bytes.size - offset)
                sink.write(bytes, offset, count)
                offset += count
                listener.notifyProgress(offset.toLong(), bytes.size.toLong(), NetworkTransferDirection.UPLOAD)
            }
        }
    }
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

private fun String.toRetrofitUrl(): String = if (startsWith("https://", ignoreCase = true)) this else removePrefix("/")

private fun redirectFailure(message: String): NetworkTransportException = NetworkTransportException(
    NetworkFailure(code = "redirect_rejected", category = NetworkFailureCategory.VALIDATION, message = message),
)

private fun responseTooLarge(maxResponseBytes: Long): NetworkTransportException = NetworkTransportException(
    NetworkFailure(
        code = "response_too_large",
        category = NetworkFailureCategory.RESPONSE_TOO_LARGE,
        message = "响应正文超过 ${maxResponseBytes}B 限制",
    ),
)

private fun IOException.toTransportException(): NetworkTransportException {
    val cancelled = message.orEmpty().contains("canceled", ignoreCase = true)
    val failure = when {
        this is SocketTimeoutException -> NetworkFailure(
            code = "request_timeout",
            category = NetworkFailureCategory.TIMEOUT,
            message = "请求超时",
            retryable = true,
        )
        this is UnknownHostException -> NetworkFailure(
            code = "dns_failure",
            category = NetworkFailureCategory.DNS,
            message = "域名解析失败",
            retryable = true,
        )
        this is SSLException -> NetworkFailure(
            code = "tls_failure",
            category = NetworkFailureCategory.TLS,
            message = "安全连接校验失败",
        )
        cancelled -> NetworkFailure(
            code = "request_cancelled",
            category = NetworkFailureCategory.CANCELLED,
            message = "请求已取消",
        )
        else -> NetworkFailure(
            code = "network_unavailable",
            category = NetworkFailureCategory.CONNECTIVITY,
            message = "网络连接不可用",
            retryable = true,
        )
    }
    return NetworkTransportException(failure)
}

public actual fun createPlatformNetworkEngine(
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy,
): NetworkEngine = RetrofitNetworkEngine(baseUrl, tlsPolicy = tlsPolicy)

/** Android 原生调用无需 Pager 时，一步创建 Retrofit 网络客户端。 */
public fun createRetrofitNetworkClient(
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy = NetworkTlsPolicy(allowCleartext = true),
    configure: NetworkClient.Builder.() -> Unit = {},
): NetworkClient = NetworkClient.Builder(RetrofitNetworkEngine(baseUrl, tlsPolicy = tlsPolicy))
    .apply(configure)
    .build()

public actual fun resolveNetworkUrl(baseUrl: String, relativePath: String): String? {
    if (com.catchzoon.network.core.isSafeAbsoluteNetworkUrl(relativePath)) return relativePath
    if (!isSafeRelativeNetworkPath(relativePath)) return null
    val encodedQuery = relativePath.substringAfter('?', "").ifEmpty { null }
    return baseUrl.toHttpUrlOrNull()
        ?.newBuilder()
        ?.addEncodedPathSegments(relativePath.substringBefore('?').trim('/'))
        ?.encodedQuery(encodedQuery)
        ?.build()
        ?.toString()
}

private interface RetrofitTransportApi {
    @GET
    suspend fun get(
        @Url path: String,
        @HeaderMap headers: Map<String, String>,
        @Tag timeout: RequestTimeout,
    ): Response<ResponseBody>

    @POST
    suspend fun post(
        @Url path: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
        @Tag timeout: RequestTimeout,
    ): Response<ResponseBody>

    @PUT
    suspend fun put(
        @Url path: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
        @Tag timeout: RequestTimeout,
    ): Response<ResponseBody>

    @PATCH
    suspend fun patch(
        @Url path: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
        @Tag timeout: RequestTimeout,
    ): Response<ResponseBody>

    @HTTP(method = "DELETE", hasBody = true)
    suspend fun delete(
        @Url path: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
        @Tag timeout: RequestTimeout,
    ): Response<ResponseBody>

    @HTTP(method = "HEAD")
    suspend fun head(
        @Url path: String,
        @HeaderMap headers: Map<String, String>,
        @Tag timeout: RequestTimeout,
    ): Response<ResponseBody>

    @HTTP(method = "OPTIONS", hasBody = true)
    suspend fun options(
        @Url path: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
        @Tag timeout: RequestTimeout,
    ): Response<ResponseBody>
}

private data class RequestTimeout(val seconds: Int)

private object PerRequestTimeoutInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val seconds = chain.request().tag(RequestTimeout::class.java)?.seconds ?: DEFAULT_TIMEOUT_SECONDS
        return chain
            .withConnectTimeout(seconds, TimeUnit.SECONDS)
            .withReadTimeout(seconds, TimeUnit.SECONDS)
            .withWriteTimeout(seconds, TimeUnit.SECONDS)
            .proceed(chain.request())
    }
}

private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false)
    .build()

private fun OkHttpClient.Builder.configureCertificatePins(policy: NetworkTlsPolicy) {
    if (policy.certificatePins.isEmpty()) return
    val systemVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    hostnameVerifier { host, session ->
        systemVerifier.verify(host, session) && policy.pinsForHost(host).let { pins ->
            pins.isEmpty() || session.peerCertificates.any { it.sha256Pin() in pins }
        }
    }
}

private fun Certificate.sha256Pin(): String = "cert-sha256/" +
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val DEFAULT_TIMEOUT_SECONDS = 30
private const val PROGRESS_CHUNK_BYTES = 32L * 1024L
private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
private val CROSS_ORIGIN_SECRET_HEADERS = setOf("Authorization", "Proxy-Authorization", "Cookie")
