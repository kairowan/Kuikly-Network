package com.catchzoon.network.core

/** 允许业务二次封装替换通用 HTTP 错误规则。 */
public fun interface NetworkErrorMapper {
    public fun map(response: NetworkRawResponse): NetworkFailure
}
