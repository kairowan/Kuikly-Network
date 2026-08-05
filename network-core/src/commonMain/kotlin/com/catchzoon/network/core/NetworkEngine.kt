package com.catchzoon.network.core

/** 平台网络实现协议；Android、iOS 或测试实现都可以替换。 */
public interface NetworkEngine {
    public suspend fun execute(request: NetworkRawRequest): NetworkRawResponse
    public fun cancelAll()
}
