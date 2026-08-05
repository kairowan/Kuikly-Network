package com.catchzoon.network.codec

import com.catchzoon.network.api.NetworkDecoder
import com.catchzoon.network.api.NetworkEncoder
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Kuikly 双端共用的 JSON 编解码入口。
 *
 * 调用方只提供 DTO 与 JSONObject 的映射规则，格式异常会由 NetworkClient 统一转换为 serialization_error。
 */
public object NetworkJson {
    /** 创建类型安全的请求序列化器。 */
    public fun <T> encoder(serializer: (T) -> JSONObject): NetworkEncoder<T> =
        NetworkEncoder { value -> serializer(value).toString() }

    /** 创建类型安全的响应解析器。 */
    public fun <T> decoder(parser: (JSONObject) -> T): NetworkDecoder<T> =
        NetworkDecoder { raw -> parser(decodeObject(raw)) }

    /** 把原始字符串严格解析为 JSON 对象，空响应按空对象处理。 */
    public fun decodeObject(raw: String): JSONObject =
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
}
