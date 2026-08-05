package com.catchzoon.network.core

/** 可插入鉴权、公共请求头、日志或缓存策略的请求链。 */
public fun interface NetworkInterceptor {
    public suspend fun intercept(chain: Chain): NetworkRawResponse

    /** 取消该拦截器持有的在途工作；无状态拦截器无需实现。 */
    public fun cancelAll(): Unit = Unit

    public interface Chain {
        public val request: NetworkRawRequest
        public suspend fun proceed(request: NetworkRawRequest = this.request): NetworkRawResponse
    }
}
