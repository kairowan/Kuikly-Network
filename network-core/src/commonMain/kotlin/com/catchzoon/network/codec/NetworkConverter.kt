package com.catchzoon.network.codec

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/**
 * 网络内容转换协议。
 *
 * 业务可替换 JSON 实现或字段策略；核心模块不依赖具体 DTO 实现。
 */
public interface NetworkConverter {
    /** 把类型化请求转换为传输正文。 */
    public fun <T> encode(serializer: SerializationStrategy<T>, value: T): String

    /** 把传输正文转换为类型化响应。 */
    public fun <T> decode(deserializer: DeserializationStrategy<T>, value: String): T
}

/** Kotlin Multiplatform 默认 JSON 转换器。 */
public class KotlinxJsonNetworkConverter(
    public val json: Json = defaultNetworkJson(),
) : NetworkConverter {
    override fun <T> encode(serializer: SerializationStrategy<T>, value: T): String =
        json.encodeToString(serializer, value)

    override fun <T> decode(deserializer: DeserializationStrategy<T>, value: String): T =
        json.decodeFromString(deserializer, value)
}

/** 提供适合接口兼容升级的默认 JSON 配置。 */
public fun defaultNetworkJson(): Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
    coerceInputValues = false
}
