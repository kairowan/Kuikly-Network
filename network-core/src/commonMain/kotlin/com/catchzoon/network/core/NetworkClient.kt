package com.catchzoon.network.core

import com.catchzoon.network.api.NetworkEndpoint
import com.catchzoon.network.api.NetworkDecoder
import com.catchzoon.network.api.NetworkEncoder
import com.catchzoon.network.codec.KotlinxJsonNetworkConverter
import com.catchzoon.network.codec.NetworkConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlin.time.TimeSource

/**
 * 公共网络客户端。
 *
 * 类型化调用统一返回 [NetworkCall]；平台层只负责传输，策略、解析、异常和可观测事件都在这里收敛。
 */
public class NetworkClient private constructor(
    private val engine: NetworkEngine,
    private val interceptors: List<NetworkInterceptor>,
    private val errorMapper: NetworkErrorMapper,
    private val eventListeners: List<NetworkEventListener>,
    private val converter: NetworkConverter,
    private val responseAdapter: NetworkResponseAdapter?,
    private val defaults: NetworkClientDefaults,
) {
    /** 执行原始请求，HTTP 错误响应仍原样返回，方便业务解析自定义错误体。 */
    public suspend fun executeRaw(request: NetworkRawRequest): NetworkRawResult {
        if (!request.isValid()) {
            return NetworkRawResult.Failure(
                NetworkFailure(
                    code = "invalid_request",
                    category = NetworkFailureCategory.VALIDATION,
                    message = "请求参数无效",
                ),
            )
        }
        val mark = TimeSource.Monotonic.markNow()
        val telemetryPath = request.relativePath.substringBefore('?')
        notifySafely(NetworkEvent.Started(telemetryPath, request.method))
        return try {
            val response = RealChain(engine, interceptors, 0, request).proceed()
                .copy(durationMillis = mark.elapsedNow().inWholeMilliseconds)
            notifySafely(
                NetworkEvent.Completed(telemetryPath, response.statusCode, response.durationMillis, response.source),
            )
            NetworkRawResult.Response(response)
        } catch (cancelled: CancellationException) {
            notifySafely(NetworkEvent.Cancelled(telemetryPath, mark.elapsedNow().inWholeMilliseconds))
            throw cancelled
        } catch (transport: NetworkTransportException) {
            val failure = transport.failure.withRequestId(request.requestId())
            notifyFailure(telemetryPath, failure, mark.elapsedNow().inWholeMilliseconds)
            NetworkRawResult.Failure(failure)
        } catch (exception: Exception) {
            val failure = exception.toNetworkFailure(request.requestId())
            notifyFailure(telemetryPath, failure, mark.elapsedNow().inWholeMilliseconds)
            NetworkRawResult.Failure(failure)
        }
    }

    /** 创建可组合的类型化调用；只有 await 或收集 asFlow 时才真正发起请求。 */
    public fun <Request, Response> call(
        endpoint: NetworkEndpoint<Request, Response>,
        request: Request,
        options: NetworkRequestOptions = NetworkRequestOptions(),
    ): NetworkCall<Response> {
        val resolvedOptions = options.resolve(defaults, endpoint.timeoutSeconds)
        val endpointHeaders = try {
            endpoint.headers(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return NetworkCall(endpoint.method, resolvedOptions) {
                NetworkResult.Failure(
                    NetworkFailure(
                        code = "serialization_error",
                        category = NetworkFailureCategory.SERIALIZATION,
                        message = "请求头无法生成",
                    ),
                )
            }
        }
        val combinedOptions = resolvedOptions.copy(headers = endpointHeaders + resolvedOptions.headers)
        return NetworkCall(endpoint.method, combinedOptions) { effective ->
            val rawRequest = try {
                val encoded = endpoint.requestEncoder.encode(request)
                val requestBody = effective.requestBody ?: encoded.takeIf(String::isNotEmpty)?.let(NetworkRequestBody::Text)
                val bodyHeaders = if (requestBody != null && !effective.headers.hasHeader("Content-Type")) {
                    effective.headers + ("Content-Type" to requestBody.contentType)
                } else {
                    effective.headers
                }
                NetworkRawRequest(
                    relativePath = endpoint.path(request),
                    method = endpoint.method,
                    body = (requestBody as? NetworkRequestBody.Text)?.value.orEmpty(),
                    bodyBytes = (requestBody as? NetworkRequestBody.Binary)?.bytes(),
                    headers = bodyHeaders,
                    timeoutSeconds = requireNotNull(effective.timeoutSeconds),
                    maxResponseBytes = requireNotNull(effective.maxResponseBytes),
                    cachePolicy = requireNotNull(effective.cachePolicy),
                    priority = requireNotNull(effective.priority),
                    tags = effective.tags,
                    redirectPolicy = requireNotNull(effective.redirectPolicy),
                    progressListener = effective.progressListener,
                    allowAbsoluteUrl = effective.allowAbsoluteUrl,
                    streamResponse = effective.streamResponse,
                    cacheTags = effective.cacheTags,
                    invalidateCacheTags = effective.invalidateCacheTags,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@NetworkCall NetworkResult.Failure(
                    NetworkFailure(
                        code = "serialization_error",
                        category = NetworkFailureCategory.SERIALIZATION,
                        message = "请求数据无法序列化",
                    ),
                )
            }
            when (val raw = executeRaw(rawRequest)) {
                is NetworkRawResult.Failure -> NetworkResult.Failure(raw.value)
                is NetworkRawResult.Response -> raw.value.toTypedResult(endpoint, rawRequest.requestId())
            }
        }
    }

    /**
     * 创建自动序列化的类型化调用。
     *
     * 这是高层 Service 和第三方二次封装共用的入口，普通页面无需直接调用。
     */
    public fun <Request, Response> typedCall(
        method: NetworkMethod,
        path: String,
        request: Request,
        requestSerializer: SerializationStrategy<Request>?,
        responseDeserializer: DeserializationStrategy<Response>,
        options: NetworkRequestOptions = NetworkRequestOptions(),
        emptyResponseFactory: (() -> Response)? = null,
    ): NetworkCall<Response> = typedCall(
        method = method,
        path = { path },
        request = request,
        requestSerializer = requestSerializer,
        responseDeserializer = responseDeserializer,
        options = options,
        emptyResponseFactory = emptyResponseFactory,
    )

    /** 创建动态路径的自动序列化调用，路径计算异常会统一收敛为请求失败。 */
    public fun <Request, Response> typedCall(
        method: NetworkMethod,
        path: (Request) -> String,
        request: Request,
        requestSerializer: SerializationStrategy<Request>?,
        responseDeserializer: DeserializationStrategy<Response>,
        options: NetworkRequestOptions = NetworkRequestOptions(),
        emptyResponseFactory: (() -> Response)? = null,
    ): NetworkCall<Response> {
        val requestEncoder = requestSerializer?.let { serializer ->
            NetworkEncoder<Request> { value -> converter.encode(serializer, value) }
        } ?: NetworkEncoder { "" }
        return call(
            endpoint = NetworkEndpoint(
                method = method,
                path = path,
                requestEncoder = requestEncoder,
                responseDecoder = NetworkDecoder { body -> converter.decode(responseDeserializer, body) },
                emptyResponseFactory = emptyResponseFactory,
            ),
            request = request,
            options = options,
        )
    }

    /** 使用已经编码的表单或 multipart 正文创建类型化调用。 */
    public fun <Response> encodedCall(
        method: NetworkMethod,
        path: String,
        body: NetworkRequestBody,
        responseDeserializer: DeserializationStrategy<Response>,
        options: NetworkRequestOptions = NetworkRequestOptions(),
        emptyResponseFactory: (() -> Response)? = null,
    ): NetworkCall<Response> = typedCall(
        method = method,
        path = path,
        request = Unit,
        requestSerializer = null,
        responseDeserializer = responseDeserializer,
        options = options.copy(requestBody = body),
        emptyResponseFactory = emptyResponseFactory,
    )

    /** 使用自定义响应解析器发送已经编码的正文，供 Gson 或二次封装复用。 */
    public fun <Response> encodedCall(
        method: NetworkMethod,
        path: String,
        body: NetworkRequestBody,
        responseDecoder: NetworkDecoder<Response>,
        options: NetworkRequestOptions = NetworkRequestOptions(),
        emptyResponseFactory: (() -> Response)? = null,
    ): NetworkCall<Response> = call(
        endpoint = NetworkEndpoint(
            method = method,
            path = { path },
            requestEncoder = NetworkEncoder<Unit> { "" },
            responseDecoder = responseDecoder,
            emptyResponseFactory = emptyResponseFactory,
        ),
        request = Unit,
        options = options.copy(requestBody = body),
    )

    /** 创建返回原始字节的下载调用，适合图片、压缩包和流式落盘适配器。 */
    public fun byteCall(
        method: NetworkMethod,
        path: String,
        options: NetworkRequestOptions = NetworkRequestOptions(streamResponse = true),
    ): NetworkCall<ByteArray> = call(
        endpoint = NetworkEndpoint(
            method = method,
            path = { path },
            requestEncoder = NetworkEncoder<Unit> { "" },
            responseDecoder = NetworkDecoder(String::encodeToByteArray),
            responseByteDecoder = com.catchzoon.network.api.NetworkCodecs.byteArrayDecoder,
            emptyResponseFactory = { ByteArray(0) },
        ),
        request = Unit,
        options = options.copy(streamResponse = true),
    )

    /** 兼容挂起式调用；新业务优先让 API 返回 [NetworkCall]。 */
    public suspend fun <Request, Response> execute(
        endpoint: NetworkEndpoint<Request, Response>,
        request: Request,
        extraHeaders: Map<String, String> = emptyMap(),
    ): NetworkResult<Response> = call(
        endpoint = endpoint,
        request = request,
        options = NetworkRequestOptions(headers = extraHeaders),
    ).await()

    /** 兼容旧 Flow 入口，内部仍走同一个 [NetworkCall] 执行链。 */
    public fun <Request, Response> flow(
        endpoint: NetworkEndpoint<Request, Response>,
        request: Request,
        extraHeaders: Map<String, String> = emptyMap(),
        retryCount: Int = 0,
        retryDelayMillis: Long = 0L,
    ): Flow<NetworkState<Response>> = call(
        endpoint = endpoint,
        request = request,
        options = NetworkRequestOptions(
            headers = extraHeaders,
            retryPolicy = NetworkRetryPolicy(
                maxAttempts = retryCount.coerceIn(0, MAX_RETRY_COUNT) + 1,
                initialDelayMillis = retryDelayMillis.coerceAtLeast(0L),
                maxDelayMillis = retryDelayMillis.coerceAtLeast(0L),
                jitterRatio = 0.0,
            ),
        ),
    ).asFlow()

    /** 取消客户端持有的全部在途工作和平台请求；客户端之后仍可继续使用。 */
    public fun cancelAll() {
        interceptors.forEach(NetworkInterceptor::cancelAll)
        engine.cancelAll()
    }

    /** 通过 Builder 配置公共能力，创建后客户端保持不可变。 */
    public class Builder(private val engine: NetworkEngine) {
        private val interceptors = mutableListOf<NetworkInterceptor>()
        private val eventListeners = mutableListOf<NetworkEventListener>()
        private var errorMapper: NetworkErrorMapper = DefaultNetworkErrorMapper
        private var converter: NetworkConverter = KotlinxJsonNetworkConverter()
        private var responseAdapter: NetworkResponseAdapter? = null
        private var defaults: NetworkClientDefaults = NetworkClientDefaults()

        /** 按添加顺序注册请求拦截器。 */
        public fun addInterceptor(interceptor: NetworkInterceptor): Builder = apply {
            interceptors += interceptor
        }

        /** 注册脱敏网络事件监听器，监听器异常不会影响请求。 */
        public fun addEventListener(listener: NetworkEventListener): Builder = apply {
            eventListeners += listener
        }

        /** 替换默认 HTTP 状态映射规则。 */
        public fun errorMapper(mapper: NetworkErrorMapper): Builder = apply {
            errorMapper = mapper
        }

        /** 替换请求和响应内容转换器。 */
        public fun converter(converter: NetworkConverter): Builder = apply {
            this.converter = converter
        }

        /** 配置统一业务响应包裹解析器。 */
        public fun responseAdapter(adapter: NetworkResponseAdapter): Builder = apply {
            responseAdapter = adapter
        }

        /** 配置客户端级默认策略；接口声明和单次调用链仍可覆盖。 */
        public fun defaults(defaults: NetworkClientDefaults): Builder = apply {
            this.defaults = defaults
        }

        /** 设置未单独声明超时的接口默认值。 */
        public fun defaultTimeout(seconds: Int): Builder = apply {
            defaults = defaults.copy(timeoutSeconds = seconds)
        }

        /** 设置未单独声明响应上限的接口默认值。 */
        public fun defaultResponseLimit(bytes: Long): Builder = apply {
            defaults = defaults.copy(maxResponseBytes = bytes)
        }

        /** 设置未单独声明重试规则的接口默认值。 */
        public fun defaultRetry(policy: NetworkRetryPolicy): Builder = apply {
            defaults = defaults.copy(retryPolicy = policy)
        }

        /** 设置未单独声明缓存规则的接口默认值。 */
        public fun defaultCache(policy: NetworkCachePolicy): Builder = apply {
            defaults = defaults.copy(cachePolicy = policy)
        }

        /** 设置默认请求优先级。 */
        public fun defaultPriority(priority: NetworkPriority): Builder = apply {
            defaults = defaults.copy(priority = priority)
        }

        /** 设置默认重定向策略。 */
        public fun defaultRedirects(policy: NetworkRedirectPolicy): Builder = apply {
            defaults = defaults.copy(redirectPolicy = policy)
        }

        /** 设置客户端共享重试预算。 */
        public fun retryBudget(budget: NetworkRetryBudget): Builder = apply {
            defaults = defaults.copy(retryBudget = budget)
        }

        /** 创建不可变客户端，后续修改 Builder 不会影响它。 */
        public fun build(): NetworkClient = NetworkClient(
            engine = engine,
            interceptors = interceptors.toList(),
            errorMapper = errorMapper,
            eventListeners = eventListeners.toList(),
            converter = converter,
            responseAdapter = responseAdapter,
            defaults = defaults,
        )
    }

    private fun <Request, Response> NetworkRawResponse.toTypedResult(
        endpoint: NetworkEndpoint<Request, Response>,
        fallbackRequestId: String,
    ): NetworkResult<Response> {
        val requestId = headers.value("X-Request-ID").ifEmpty { fallbackRequestId }
        val payload = try {
            responseAdapter?.adapt(this) ?: if (statusCode in 200..299) {
                NetworkPayload.Data(body)
            } else {
                NetworkPayload.Failure(errorMapper.map(this))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return NetworkResult.Failure(
                NetworkFailure(
                    code = "response_adapter_error",
                    category = NetworkFailureCategory.SERIALIZATION,
                    message = "统一响应协议解析失败",
                    statusCode = statusCode,
                    requestId = requestId,
                ),
            )
        }
        if (payload is NetworkPayload.Failure) {
            return NetworkResult.Failure(payload.failure.withRequestId(requestId))
        }
        return try {
            NetworkResult.Success(
                data = if (statusCode == 204 && body.isEmpty() && endpoint.emptyResponseFactory != null) {
                    endpoint.emptyResponseFactory.invoke()
                } else if (endpoint.responseByteDecoder != null && bodyBytes != null) {
                    endpoint.responseByteDecoder.decode(bodyBytes)
                } else {
                    endpoint.responseDecoder.decode((payload as NetworkPayload.Data).body)
                },
                statusCode = statusCode,
                headers = headers,
                requestId = requestId,
                durationMillis = durationMillis,
                source = source,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NetworkResult.Failure(
                NetworkFailure(
                    code = "serialization_error",
                    category = NetworkFailureCategory.SERIALIZATION,
                    statusCode = statusCode,
                    message = "响应数据无法解析",
                    requestId = requestId,
                ),
            )
        }
    }

    private suspend fun notifySafely(event: NetworkEvent) {
        eventListeners.forEach { listener ->
            try {
                listener.onEvent(event)
            } catch (_: Exception) {
                // 监控是旁路能力，不能改变业务请求结果。
            }
        }
    }

    private suspend fun notifyFailure(path: String, failure: NetworkFailure, durationMillis: Long) {
        notifySafely(
            if (failure.category == NetworkFailureCategory.CANCELLED) {
                NetworkEvent.Cancelled(path, durationMillis)
            } else {
                NetworkEvent.Failed(path, failure, durationMillis)
            },
        )
    }

    private class RealChain(
        private val engine: NetworkEngine,
        private val interceptors: List<NetworkInterceptor>,
        private val index: Int,
        override val request: NetworkRawRequest,
    ) : NetworkInterceptor.Chain {
        override suspend fun proceed(request: NetworkRawRequest): NetworkRawResponse =
            if (index >= interceptors.size) {
                engine.execute(request)
            } else {
                interceptors[index].intercept(RealChain(engine, interceptors, index + 1, request))
            }
    }

    private companion object {
        const val MAX_RETRY_COUNT = 5
    }
}

private object DefaultNetworkErrorMapper : NetworkErrorMapper {
    override fun map(response: NetworkRawResponse): NetworkFailure = NetworkFailure(
        code = when (response.statusCode) {
            401 -> "unauthorized"
            403 -> "forbidden"
            404 -> "not_found"
            408 -> "request_timeout"
            409 -> "conflict"
            413 -> "payload_too_large"
            429 -> "rate_limited"
            else -> "http_error"
        },
        category = if (response.statusCode == 408) NetworkFailureCategory.TIMEOUT else NetworkFailureCategory.HTTP,
        message = response.errorMessage,
        statusCode = response.statusCode,
        retryable = response.statusCode == 408 || response.statusCode == 429 || response.statusCode >= 500,
        requestId = response.headers.value("X-Request-ID"),
        retryAfterMillis = response.retryAfterMillis(),
    )
}

private fun NetworkRawRequest.isValid(): Boolean =
    (isSafeRelativeNetworkPath(relativePath) || allowAbsoluteUrl && isSafeAbsoluteNetworkUrl(relativePath)) &&
        timeoutSeconds in 1..300 &&
        maxResponseBytes in 1..20L * 1024L * 1024L && headers.isValidHeaders() &&
        (bodyBytes?.size ?: body.encodeToByteArray().size) <= MAX_REQUEST_BYTES

private fun NetworkRawRequest.requestId(): String = headers.value("X-Request-ID")

private fun Map<String, String>.value(name: String): String =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value.orEmpty()

private fun Map<String, String>.hasHeader(name: String): Boolean =
    keys.any { it.equals(name, ignoreCase = true) }

private fun Map<String, String>.isValidHeaders(): Boolean = size <= MAX_HEADER_COUNT && all { (name, value) ->
    name.length in 1..MAX_HEADER_NAME_LENGTH && name.all { it.isLetterOrDigit() || it in HEADER_TOKEN_SYMBOLS } &&
        value.length <= MAX_HEADER_VALUE_LENGTH && value.all { it == '\t' || it.code in 32..126 }
}

private fun NetworkRawResponse.retryAfterMillis(): Long {
    // ponytail: 先支持 Retry-After 的秒数形式；需要 HTTP-date 时再接入跨平台日期解析器。
    val seconds = headers.value("Retry-After").trim().toLongOrNull() ?: return 0L
    return seconds.coerceIn(0L, MAX_RETRY_AFTER_SECONDS) * 1_000L
}

private fun NetworkFailure.withRequestId(fallback: String): NetworkFailure =
    if (requestId.isNotEmpty() || fallback.isEmpty()) this else copy(requestId = fallback)

private fun NetworkRequestOptions.resolve(
    defaults: NetworkClientDefaults,
    endpointTimeoutSeconds: Int?,
): NetworkRequestOptions = copy(
    timeoutSeconds = timeoutSeconds ?: endpointTimeoutSeconds ?: defaults.timeoutSeconds,
    maxResponseBytes = maxResponseBytes ?: defaults.maxResponseBytes,
    retryPolicy = retryPolicy ?: defaults.retryPolicy,
    cachePolicy = cachePolicy ?: defaults.cachePolicy,
    priority = priority ?: defaults.priority,
    redirectPolicy = redirectPolicy ?: defaults.redirectPolicy,
    retryBudget = retryBudget ?: defaults.retryBudget,
)

private fun Exception.toNetworkFailure(requestId: String): NetworkFailure {
    val details = message.orEmpty()
    val timeout = details.contains("timed out", ignoreCase = true) || details.contains("timeout", ignoreCase = true)
    val cancelled = details.contains("canceled", ignoreCase = true) || details.contains("cancelled", ignoreCase = true)
    val dnsFailure = details.contains("unknownhost", ignoreCase = true) || details.contains("dns", ignoreCase = true)
    val tlsFailure = details.contains("certificate", ignoreCase = true) || details.contains("ssl", ignoreCase = true) ||
        details.contains("tls", ignoreCase = true)
    return NetworkFailure(
        code = when {
            timeout -> "request_timeout"
            cancelled -> "request_cancelled"
            dnsFailure -> "dns_failure"
            tlsFailure -> "tls_failure"
            else -> "network_unavailable"
        },
        category = when {
            timeout -> NetworkFailureCategory.TIMEOUT
            cancelled -> NetworkFailureCategory.CANCELLED
            dnsFailure -> NetworkFailureCategory.DNS
            tlsFailure -> NetworkFailureCategory.TLS
            else -> NetworkFailureCategory.CONNECTIVITY
        },
        message = when {
            timeout -> "请求超时"
            cancelled -> "请求已取消"
            dnsFailure -> "域名解析失败"
            tlsFailure -> "安全连接校验失败"
            else -> "网络连接不可用"
        },
        retryable = !cancelled && !tlsFailure,
        requestId = requestId,
    )
}

private const val MAX_REQUEST_BYTES = 20 * 1024 * 1024
private const val MAX_HEADER_COUNT = 64
private const val MAX_HEADER_NAME_LENGTH = 128
private const val MAX_HEADER_VALUE_LENGTH = 8 * 1024
private const val MAX_RETRY_AFTER_SECONDS = 300L
private const val HEADER_TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"
