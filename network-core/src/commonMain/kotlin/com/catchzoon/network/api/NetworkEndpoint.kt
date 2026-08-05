package com.catchzoon.network.api

import com.catchzoon.network.core.NetworkMethod

/** 把请求模型序列化为平台传输层可发送的字符串。 */
public fun interface NetworkEncoder<in T> {
    public fun encode(value: T): String
}

/** 把原始响应解析为调用方需要的类型。 */
public fun interface NetworkDecoder<out T> {
    public fun decode(value: String): T
}

/** 把二进制响应解析为调用方需要的类型。 */
public fun interface NetworkByteDecoder<out T> {
    public fun decode(value: ByteArray): T
}

/**
 * 类似 Retrofit 接口方法的跨平台声明。
 *
 * API 类只组合 Endpoint，不依赖 OkHttp、Retrofit 或 Kuikly 原生回调，因此可以继续进行业务二次封装。
 */
public class NetworkEndpoint<Request, Response>(
    public val method: NetworkMethod,
    public val path: (Request) -> String,
    public val requestEncoder: NetworkEncoder<Request>,
    public val responseDecoder: NetworkDecoder<Response>,
    public val responseByteDecoder: NetworkByteDecoder<Response>? = null,
    public val emptyResponseFactory: (() -> Response)? = null,
    public val headers: (Request) -> Map<String, String> = { emptyMap() },
    public val timeoutSeconds: Int? = null,
)

/** 常用的无请求体、原始字符串编解码器。 */
public object NetworkCodecs {
    public val unitEncoder: NetworkEncoder<Unit> = NetworkEncoder { "" }
    public val stringEncoder: NetworkEncoder<String> = NetworkEncoder { it }
    public val stringDecoder: NetworkDecoder<String> = NetworkDecoder { it }
    public val byteArrayDecoder: NetworkByteDecoder<ByteArray> = NetworkByteDecoder(ByteArray::copyOf)
}
