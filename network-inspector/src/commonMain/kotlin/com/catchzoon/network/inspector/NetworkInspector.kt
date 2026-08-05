package com.catchzoon.network.inspector

import com.catchzoon.network.core.MonotonicNetworkClock
import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkMethod
import com.catchzoon.network.core.NetworkTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 单次网络交换的脱敏快照，不保存原始正文。 */
public data class NetworkExchange(
    val id: Long,
    val method: NetworkMethod,
    val path: String,
    val requestHeaders: Map<String, String>,
    val requestBytes: Int,
    val requestTags: Set<String>,
    val startedAtMillis: Long,
    val durationMillis: Long? = null,
    val statusCode: Int? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBytes: Int? = null,
    val failure: NetworkFailure? = null,
)

/** Inspector 脱敏规则；默认覆盖身份凭据、Cookie 和常见 token 查询参数。 */
public data class NetworkRedactionPolicy(
    val headerNames: Set<String> = DEFAULT_SECRET_HEADERS,
    val queryNames: Set<String> = DEFAULT_SECRET_QUERY_NAMES,
) {
    internal fun headers(values: Map<String, String>): Map<String, String> = values.mapValues { (name, value) ->
        if (headerNames.any { it.equals(name, ignoreCase = true) }) REDACTED else value
    }

    internal fun path(value: String): String {
        val queryStart = value.indexOf('?')
        if (queryStart < 0) return value
        val path = value.substring(0, queryStart)
        val query = value.substring(queryStart + 1).split('&').joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            if (queryNames.any { it.equals(name, ignoreCase = true) }) "$name=$REDACTED" else pair
        }
        return "$path?$query"
    }

    public companion object {
        private val DEFAULT_SECRET_HEADERS = setOf("Authorization", "Cookie", "Set-Cookie", "X-Api-Key")
        private val DEFAULT_SECRET_QUERY_NAMES = setOf("token", "access_token", "refresh_token", "api_key", "password")
        private const val REDACTED = "██"
    }
}

/**
 * 有界、只读暴露的网络调试器。
 *
 * 正文只记录字节数，调用方无需再担心调试日志意外落盘用户内容。
 */
public class NetworkInspector(
    private val maxEntries: Int = 100,
    private val redaction: NetworkRedactionPolicy = NetworkRedactionPolicy(),
) {
    private val lock = Mutex()
    private val mutableExchanges = MutableStateFlow<List<NetworkExchange>>(emptyList())
    private var nextId = 0L

    init {
        require(maxEntries in 1..1_000) { "maxEntries 必须在 1..1000 之间" }
    }

    public val exchanges: StateFlow<List<NetworkExchange>> = mutableExchanges.asStateFlow()

    /** 清空当前进程内快照。 */
    public suspend fun clear(): Unit = lock.withLock { mutableExchanges.value = emptyList() }

    /** 创建需要添加到 NetworkClient 的拦截器。 */
    public fun interceptor(): NetworkInterceptor = NetworkInterceptor { chain ->
        val request = chain.request
        val startedAt = MonotonicNetworkClock.nowMillis()
        val id = lock.withLock {
            val value = ++nextId
            append(
                NetworkExchange(
                    id = value,
                    method = request.method,
                    path = redaction.path(request.relativePath),
                    requestHeaders = redaction.headers(request.headers),
                    requestBytes = request.bodyBytes?.size ?: request.body.encodeToByteArray().size,
                    requestTags = request.tags,
                    startedAtMillis = startedAt,
                ),
            )
            value
        }
        try {
            val response = chain.proceed()
            update(id) { exchange ->
                exchange.copy(
                    durationMillis = MonotonicNetworkClock.nowMillis() - startedAt,
                    statusCode = response.statusCode,
                    responseHeaders = redaction.headers(response.headers),
                    responseBytes = response.bodyBytes?.size ?: response.body.encodeToByteArray().size,
                )
            }
            response
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (transport: NetworkTransportException) {
            update(id) {
                it.copy(
                    durationMillis = MonotonicNetworkClock.nowMillis() - startedAt,
                    failure = transport.failure,
                )
            }
            throw transport
        } catch (throwable: Exception) {
            update(id) { it.copy(durationMillis = MonotonicNetworkClock.nowMillis() - startedAt) }
            throw throwable
        }
    }

    private fun append(exchange: NetworkExchange) {
        mutableExchanges.value = (mutableExchanges.value + exchange).takeLast(maxEntries)
    }

    private suspend fun update(id: Long, transform: (NetworkExchange) -> NetworkExchange) = lock.withLock {
        mutableExchanges.value = mutableExchanges.value.map { if (it.id == id) transform(it) else it }
    }
}

/** 把 Inspector 接入客户端请求链。 */
public fun NetworkClient.Builder.inspector(value: NetworkInspector): NetworkClient.Builder =
    addInterceptor(value.interceptor())
