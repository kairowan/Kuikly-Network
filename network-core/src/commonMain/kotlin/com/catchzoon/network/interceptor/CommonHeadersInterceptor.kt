package com.catchzoon.network.interceptor

import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkRawRequest

/**
 * 动态添加鉴权或公共请求头；Endpoint 自己声明的请求头拥有更高优先级。
 * HeaderProvider 可以读取安全存储中的最新令牌，避免客户端初始化时缓存旧值。
 */
public class CommonHeadersInterceptor(
    private val headerProvider: suspend (NetworkRawRequest) -> Map<String, String>,
) : NetworkInterceptor {
    override suspend fun intercept(chain: NetworkInterceptor.Chain) = chain.proceed(
        chain.request.copy(headers = headerProvider(chain.request) + chain.request.headers),
    )
}
