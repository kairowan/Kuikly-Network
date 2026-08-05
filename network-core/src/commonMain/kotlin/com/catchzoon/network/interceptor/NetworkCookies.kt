package com.catchzoon.network.interceptor

import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.MonotonicNetworkClock
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 客户端 Cookie；公共层只处理 Max-Age，Expires 可由平台持久化实现补充。 */
public data class NetworkCookie(
    val name: String,
    val value: String,
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
    val expiresAtMillis: Long? = null,
)

/** Cookie 存储协议，业务可以替换为 Keychain/Keystore 加密实现。 */
public interface NetworkCookieStore {
    public suspend fun load(request: NetworkRawRequest): List<NetworkCookie>
    public suspend fun save(request: NetworkRawRequest, response: NetworkRawResponse)
    public suspend fun clear()
}

/** 有界的进程内 CookieStore，不持久化身份凭据。 */
public class MemoryNetworkCookieStore(
    private val maxCookies: Int = 128,
    private val nowMillis: () -> Long = MonotonicNetworkClock::nowMillis,
) : NetworkCookieStore {
    private val mutex = Mutex()
    private val cookies = linkedMapOf<String, NetworkCookie>()

    init {
        require(maxCookies in 1..1_024) { "maxCookies 必须在 1..1024 之间" }
    }

    override suspend fun load(request: NetworkRawRequest): List<NetworkCookie> = mutex.withLock {
        if (request.relativePath.startsWith("https://")) return@withLock emptyList()
        val now = nowMillis()
        cookies.entries.removeAll { (_, cookie) -> cookie.expiresAtMillis?.let { it <= now } == true }
        val path = request.relativePath.substringBefore('?')
        cookies.values.filter { cookie -> path.startsWith(cookie.path) }
    }

    override suspend fun save(request: NetworkRawRequest, response: NetworkRawResponse) {
        if (request.relativePath.startsWith("https://")) return
        val values = response.headerValues.entries
            .filter { (name, _) -> name.equals("Set-Cookie", ignoreCase = true) }
            .flatMap(Map.Entry<String, List<String>>::value)
        if (values.isEmpty()) return
        mutex.withLock {
            values.mapNotNull { it.parseCookie(nowMillis()) }.forEach { cookie ->
                val key = "${cookie.path}\u0000${cookie.name.lowercase()}"
                if (cookie.expiresAtMillis?.let { it <= nowMillis() } == true) cookies.remove(key)
                else {
                    if (cookies.size >= maxCookies && key !in cookies) cookies.remove(cookies.keys.first())
                    cookies[key] = cookie
                }
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock { cookies.clear() }
    }
}

/** 自动发送和保存 Cookie；显式 Cookie 请求头优先。 */
public class NetworkCookieInterceptor(
    private val store: NetworkCookieStore,
) : NetworkInterceptor {
    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        val request = if (chain.request.headers.keys.any { it.equals("Cookie", ignoreCase = true) }) {
            chain.request
        } else {
            val cookie = store.load(chain.request).joinToString("; ") { "${it.name}=${it.value}" }
            if (cookie.isEmpty()) chain.request else chain.request.copy(headers = chain.request.headers + ("Cookie" to cookie))
        }
        return chain.proceed(request).also { response -> store.save(request, response) }
    }
}

private fun String.parseCookie(nowMillis: Long): NetworkCookie? {
    val segments = split(';').map(String::trim)
    val pair = segments.firstOrNull()?.split('=', limit = 2) ?: return null
    val name = pair.getOrNull(0).orEmpty()
    val value = pair.getOrNull(1).orEmpty()
    if (!name.isSafeCookieToken() || value.any { it == '\r' || it == '\n' || it == ';' }) return null
    val attributes = segments.drop(1).associate { segment ->
        segment.substringBefore('=').lowercase() to segment.substringAfter('=', "")
    }
    val maxAge = attributes["max-age"]?.toLongOrNull()
    return NetworkCookie(
        name = name,
        value = value,
        path = attributes["path"]?.takeIf { it.startsWith('/') && ".." !in it } ?: "/",
        secure = "secure" in attributes,
        httpOnly = "httponly" in attributes,
        expiresAtMillis = maxAge?.let { nowMillis + it.coerceAtLeast(0L) * 1_000L },
    )
}

private fun String.isSafeCookieToken(): Boolean =
    isNotEmpty() && length <= 128 && all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }
