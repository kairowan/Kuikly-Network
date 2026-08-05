package com.catchzoon.network.api

import com.catchzoon.network.core.NetworkCall
import com.catchzoon.network.core.NetworkCachePolicy
import com.catchzoon.network.core.NetworkRequestOptions
import com.catchzoon.network.core.NetworkRetryPolicy
import com.catchzoon.network.core.isSafeRelativeNetworkPath

/**
 * Retrofit 风格的不可变接口声明。
 *
 * URL、HTTP 方法、动态参数和默认操作符在 API 类初始化时集中定义，调用时只传业务参数。
 */
public class NetworkRoute<Request, Response> @PublishedApi internal constructor(
    private val pathTemplate: String,
    private val configuration: NetworkRouteConfiguration<Request>,
    private val callFactory: (
        path: (Request) -> String,
        request: Request,
        options: NetworkRequestOptions,
    ) -> NetworkCall<Response>,
) {
    /** 使用业务参数创建一次冷调用。 */
    public operator fun invoke(request: Request): NetworkCall<Response> = callFactory(
        { value -> configuration.resolvePath(pathTemplate, value) },
        request,
        configuration.options(request),
    )
}

/** 无参数接口声明，调用方可以直接使用 api.route()。 */
public class EmptyNetworkRoute<Response> @PublishedApi internal constructor(
    private val route: NetworkRoute<Unit, Response>,
) {
    public operator fun invoke(): NetworkCall<Response> = route(Unit)
}

/** 配置单个接口的路径参数、请求头和默认调用策略。 */
public class NetworkRouteBuilder<Request> {
    private val pathProviders = linkedMapOf<String, (Request) -> String>()
    private val queryProviders = mutableListOf<Pair<String, (Request) -> String?>>()
    private val headerProviders = mutableListOf<Pair<String, (Request) -> String?>>()
    private var timeoutSeconds: Int? = null
    private var maxResponseBytes: Long? = null
    private var retryPolicy: NetworkRetryPolicy? = null
    private var cachePolicy: NetworkCachePolicy? = null

    /** 绑定 `{name}` 动态路径参数。 */
    public fun path(name: String, value: (Request) -> String) {
        require(name.isRouteParameterName() && name !in pathProviders) { "路径参数名称重复或无效" }
        pathProviders[name] = value
    }

    /** 声明动态查询参数，返回 null 时不发送。 */
    public fun query(name: String, value: (Request) -> String?) {
        require(name.isNotBlank()) { "查询参数名称不能为空" }
        queryProviders += name to value
    }

    /** 声明当前接口专属请求头。 */
    public fun header(name: String, value: (Request) -> String?) {
        require(name.isNotBlank()) { "请求头名称不能为空" }
        headerProviders += name to value
    }

    /** 声明写请求幂等键，同时允许安全自动重试。 */
    public fun idempotencyKey(value: (Request) -> String) {
        header("Idempotency-Key") { request ->
            value(request).also { key ->
                require(key.isNotBlank() && key.length <= 191) { "幂等键长度必须在 1..191 之间" }
            }
        }
    }

    /** 设置接口超时。 */
    public fun timeout(seconds: Int) {
        require(seconds in 1..300) { "接口超时必须在 1..300 秒之间" }
        timeoutSeconds = seconds
    }

    /** 设置完整重试策略。 */
    public fun retry(policy: NetworkRetryPolicy) {
        retryPolicy = policy
    }

    /** 使用有限指数退避快速配置重试次数。 */
    public fun retry(maxAttempts: Int) {
        retryPolicy = NetworkRetryPolicy(maxAttempts = maxAttempts)
    }

    /** 设置当前接口允许的最大响应正文。 */
    public fun responseLimit(bytes: Long) {
        require(bytes in 1L..MAX_RESPONSE_BYTES) { "响应大小限制超出允许范围" }
        maxResponseBytes = bytes
    }

    /** 声明当前 GET 接口的缓存和过期兜底策略。 */
    public fun cache(maxAgeSeconds: Int, staleIfErrorSeconds: Int = 0) {
        cachePolicy = NetworkCachePolicy(maxAgeSeconds, staleIfErrorSeconds)
    }

    @PublishedApi
    internal fun build(pathTemplate: String): NetworkRouteConfiguration<Request> {
        require(!pathTemplate.contains('?') && pathTemplate.startsWith('/')) { "接口声明只能包含固定相对路径" }
        val declaredParameters = ROUTE_PARAMETER.findAll(pathTemplate).map { it.groupValues[1] }.toSet()
        require(declaredParameters == pathProviders.keys) { "接口路径参数声明和绑定不一致" }
        val validationPath = ROUTE_PARAMETER.replace(pathTemplate, "value")
        require(!validationPath.contains('{') && !validationPath.contains('}') && isSafeRelativeNetworkPath(validationPath)) {
            "接口路径模板无效"
        }
        return NetworkRouteConfiguration(
            pathProviders = pathProviders.toMap(),
            queryProviders = queryProviders.toList(),
            headerProviders = headerProviders.toList(),
            timeoutSeconds = timeoutSeconds,
            maxResponseBytes = maxResponseBytes,
            retryPolicy = retryPolicy,
            cachePolicy = cachePolicy,
        )
    }
}

@PublishedApi
internal class NetworkRouteConfiguration<Request>(
    private val pathProviders: Map<String, (Request) -> String>,
    private val queryProviders: List<Pair<String, (Request) -> String?>>,
    private val headerProviders: List<Pair<String, (Request) -> String?>>,
    private val timeoutSeconds: Int?,
    private val maxResponseBytes: Long?,
    private val retryPolicy: NetworkRetryPolicy?,
    private val cachePolicy: NetworkCachePolicy?,
) {
    fun resolvePath(template: String, request: Request): String {
        var path = template
        pathProviders.forEach { (name, provider) ->
            val value = provider(request)
            require(value.isNotEmpty()) { "路径参数 $name 不能为空" }
            path = path.replace("{$name}", encodeNetworkComponent(value))
        }
        val query = queryProviders.mapNotNull { (name, provider) ->
            provider(request)?.let { encodeNetworkComponent(name) to encodeNetworkComponent(it) }
        }
        if (query.isEmpty()) return path
        return path + query.joinToString(prefix = "?", separator = "&") { (name, value) -> "$name=$value" }
    }

    fun options(request: Request): NetworkRequestOptions = NetworkRequestOptions(
        headers = buildMap {
            headerProviders.forEach { (name, provider) -> provider(request)?.let { put(name, it) } }
        },
        timeoutSeconds = timeoutSeconds,
        maxResponseBytes = maxResponseBytes,
        retryPolicy = retryPolicy,
        cachePolicy = cachePolicy,
    )
}

private fun String.isRouteParameterName(): Boolean = matches(ROUTE_PARAMETER_NAME)

private val ROUTE_PARAMETER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}")
private val ROUTE_PARAMETER_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
private const val MAX_RESPONSE_BYTES = 20L * 1024L * 1024L
