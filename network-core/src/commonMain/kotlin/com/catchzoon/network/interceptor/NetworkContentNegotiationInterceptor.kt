package com.catchzoon.network.interceptor

import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkRawResponse

/** 给未显式声明 Accept 的请求添加内容协商头。 */
public class NetworkContentNegotiationInterceptor(
    private val acceptedContentTypes: List<String> = listOf("application/json"),
) : NetworkInterceptor {
    init {
        require(acceptedContentTypes.isNotEmpty() && acceptedContentTypes.all(String::isSafeContentType)) {
            "Accept 类型无效"
        }
    }

    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        if (chain.request.headers.keys.any { it.equals("Accept", ignoreCase = true) }) return chain.proceed()
        return chain.proceed(
            chain.request.copy(headers = chain.request.headers + ("Accept" to acceptedContentTypes.joinToString(", "))),
        )
    }
}

private fun String.isSafeContentType(): Boolean =
    isNotBlank() && length <= 127 && none { it == '\r' || it == '\n' || it.code < 32 }
