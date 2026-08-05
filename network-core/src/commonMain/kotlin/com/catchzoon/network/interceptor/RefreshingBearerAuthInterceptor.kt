package com.catchzoon.network.interceptor

import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 支持并发收敛的 Bearer 鉴权拦截器。
 *
 * 多个请求同时收到 401 时只执行一次刷新；刷新成功后每个请求最多安全重放一次。
 */
public class RefreshingBearerAuthInterceptor(
    private val currentToken: suspend () -> String?,
    private val refreshToken: suspend (expiredToken: String?) -> String?,
    private val shouldAuthenticate: (NetworkRawRequest) -> Boolean = { true },
) : NetworkInterceptor {
    private val refreshMutex = Mutex()

    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        val original = chain.request
        if (!shouldAuthenticate(original) || original.headers.hasAuthorization()) return chain.proceed()
        val observedToken = currentToken().normalizedToken()
        val firstResponse = chain.proceed(original.withBearer(observedToken))
        if (firstResponse.statusCode != HTTP_UNAUTHORIZED) return firstResponse

        val usableToken = refreshMutex.withLock {
            val latestToken = currentToken().normalizedToken()
            if (latestToken != null && latestToken != observedToken) latestToken else refreshToken(observedToken).normalizedToken()
        } ?: return firstResponse
        return chain.proceed(original.withBearer(usableToken))
    }
}

private fun NetworkRawRequest.withBearer(token: String?): NetworkRawRequest =
    if (token == null) this else copy(headers = headers + ("Authorization" to "Bearer $token"))

private fun Map<String, String>.hasAuthorization(): Boolean =
    keys.any { it.equals("Authorization", ignoreCase = true) }

private fun String?.normalizedToken(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private const val HTTP_UNAUTHORIZED = 401
