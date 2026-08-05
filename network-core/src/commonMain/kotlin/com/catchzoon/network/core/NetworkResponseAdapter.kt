package com.catchzoon.network.core

/** 统一响应适配结果：成功时提供真实业务正文，失败时提供结构化错误。 */
public sealed interface NetworkPayload {
    public data class Data(val body: String) : NetworkPayload
    public data class Failure(val failure: NetworkFailure) : NetworkPayload
}

/**
 * 服务端响应包裹适配器。
 *
 * 可在客户端创建时统一拆解 `{ code, data, message }` 等外层协议，业务 API 无需重复判断。
 */
public fun interface NetworkResponseAdapter {
    public fun adapt(response: NetworkRawResponse): NetworkPayload
}
