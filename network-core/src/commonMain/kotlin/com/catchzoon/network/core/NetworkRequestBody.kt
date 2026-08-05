package com.catchzoon.network.core

import com.catchzoon.network.api.encodeNetworkComponent

/** 已编码的 HTTP 请求正文。 */
public sealed interface NetworkRequestBody {
    public val contentType: String
    public fun bytes(): ByteArray

    public data class Text(
        val value: String,
        override val contentType: String = JSON_CONTENT_TYPE,
    ) : NetworkRequestBody {
        override fun bytes(): ByteArray = value.encodeToByteArray()
    }

    public data class Binary(
        val value: ByteArray,
        override val contentType: String = OCTET_STREAM_CONTENT_TYPE,
    ) : NetworkRequestBody {
        override fun bytes(): ByteArray = value.copyOf()
    }
}

/** multipart/form-data 的单个字段。 */
public data class NetworkMultipartPart(
    val name: String,
    val bytes: ByteArray,
    val fileName: String? = null,
    val contentType: String? = null,
) {
    init {
        require(name.isSafeDispositionValue()) { "multipart 字段名称无效" }
        require(fileName == null || fileName.isSafeDispositionValue()) { "multipart 文件名无效" }
        require(contentType == null || contentType.isSafeContentType()) { "multipart Content-Type 无效" }
    }

    public companion object {
        /** 创建普通文本字段。 */
        public fun text(name: String, value: String): NetworkMultipartPart = NetworkMultipartPart(
            name = name,
            bytes = value.encodeToByteArray(),
            contentType = TEXT_CONTENT_TYPE,
        )

        /** 创建二进制文件字段。 */
        public fun file(
            name: String,
            bytes: ByteArray,
            fileName: String,
            contentType: String = OCTET_STREAM_CONTENT_TYPE,
        ): NetworkMultipartPart = NetworkMultipartPart(name, bytes, fileName, contentType)
    }
}

/** 编码 application/x-www-form-urlencoded 正文。 */
public fun networkFormBody(fields: List<Pair<String, String?>>): NetworkRequestBody.Text = NetworkRequestBody.Text(
    value = fields.mapNotNull { (name, value) ->
        value?.let { "${encodeNetworkComponent(name)}=${encodeNetworkComponent(it)}" }
    }.joinToString("&"),
    contentType = FORM_CONTENT_TYPE,
)

/** 编码 multipart/form-data 正文；边界由字段内容生成，便于重放和测试。 */
public fun networkMultipartBody(parts: List<NetworkMultipartPart>): NetworkRequestBody.Binary {
    require(parts.isNotEmpty()) { "multipart 至少需要一个字段" }
    val boundary = "Catchzoon-${parts.stableBoundary()}"
    val output = mutableListOf<Byte>()
    parts.forEach { part ->
        output.appendUtf8("--$boundary\r\n")
        output.appendUtf8("Content-Disposition: form-data; name=\"${part.name}\"")
        part.fileName?.let { output.appendUtf8("; filename=\"$it\"") }
        output.appendUtf8("\r\n")
        part.contentType?.let { output.appendUtf8("Content-Type: $it\r\n") }
        output.appendUtf8("\r\n")
        output.addAll(part.bytes.toList())
        output.appendUtf8("\r\n")
    }
    output.appendUtf8("--$boundary--\r\n")
    return NetworkRequestBody.Binary(output.toByteArray(), "multipart/form-data; boundary=$boundary")
}

/** 把 String 参数转换为 multipart 文本字段。 */
public fun networkMultipartPart(
    name: String,
    value: String,
    fileName: String = "",
    contentType: String = TEXT_CONTENT_TYPE,
): NetworkMultipartPart = if (fileName.isEmpty()) {
    NetworkMultipartPart.text(name, value)
} else {
    NetworkMultipartPart.file(name, value.encodeToByteArray(), fileName, contentType)
}

/** 把 ByteArray 参数转换为 multipart 文件字段。 */
public fun networkMultipartPart(
    name: String,
    value: ByteArray,
    fileName: String = "blob",
    contentType: String = OCTET_STREAM_CONTENT_TYPE,
): NetworkMultipartPart = NetworkMultipartPart.file(name, value, fileName.ifEmpty { "blob" }, contentType)

/** 复用调用方已经创建的 multipart 字段。 */
public fun networkMultipartPart(
    name: String,
    value: NetworkMultipartPart,
    fileName: String = "",
    contentType: String = OCTET_STREAM_CONTENT_TYPE,
): NetworkMultipartPart = value.copy(
    name = name,
    fileName = fileName.ifEmpty { value.fileName },
    contentType = value.contentType ?: contentType,
)

private fun List<NetworkMultipartPart>.stableBoundary(): String {
    var hash = FNV_OFFSET_BASIS
    forEach { part ->
        (part.name.encodeToByteArray() + part.bytes).forEach { byte ->
            hash = (hash xor byte.toUByte().toULong()) * FNV_PRIME
        }
    }
    return hash.toString(16).padStart(16, '0')
}

private fun MutableList<Byte>.appendUtf8(value: String) {
    addAll(value.encodeToByteArray().toList())
}

private fun String.isSafeDispositionValue(): Boolean =
    isNotBlank() && length <= 255 && none { it == '\r' || it == '\n' || it == '"' || it.code < 32 }

private fun String.isSafeContentType(): Boolean =
    isNotBlank() && length <= 127 && none { it == '\r' || it == '\n' || it.code < 32 }

private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
private const val TEXT_CONTENT_TYPE = "text/plain; charset=utf-8"
private const val OCTET_STREAM_CONTENT_TYPE = "application/octet-stream"
private const val FNV_OFFSET_BASIS: ULong = 14_695_981_039_346_656_037UL
private const val FNV_PRIME: ULong = 1_099_511_628_211UL
