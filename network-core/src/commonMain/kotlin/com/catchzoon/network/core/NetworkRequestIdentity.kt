package com.catchzoon.network.core

/**
 * 为请求合并和缓存生成稳定且不包含凭据明文的键；链路追踪 ID 不改变响应语义，因此不参与计算。
 *
 * ponytail: 公共 KMP 不引入整套加密库，只用双 64 位散列隐藏内存键；若缓存键需要跨进程防碰撞，
 * 持久化实现应在平台侧再使用 SHA-256 编码。
 */
internal fun NetworkRawRequest.stableIdentityKey(includeTransportPolicy: Boolean): String = buildString {
    appendPart(method.name)
    appendPart(relativePath)
    appendPart(body)
    bodyBytes?.let { bytes -> appendPart(bytes.joinToString(separator = "") { it.toUByte().toString(16) }) }
    if (includeTransportPolicy) {
        appendPart(timeoutSeconds.toString())
        appendPart(maxResponseBytes.toString())
    }
    headers.entries
        .filterNot { it.key.equals(REQUEST_ID_HEADER, ignoreCase = true) }
        .sortedWith(compareBy({ it.key.lowercase() }, Map.Entry<String, String>::value))
        .forEach { (name, value) ->
            appendPart(name.lowercase())
            appendPart(value)
        }
}.opaqueIdentity()

private fun StringBuilder.appendPart(value: String) {
    append(value.length).append(':').append(value)
}

private fun String.opaqueIdentity(): String = encodeToByteArray().let { bytes ->
    bytes.fnv1a64(FNV_OFFSET_BASIS).toString(16).padStart(16, '0') +
        bytes.fnv1a64(FNV_SECOND_OFFSET_BASIS).toString(16).padStart(16, '0')
}

private fun ByteArray.fnv1a64(seed: ULong): ULong = fold(seed) { hash, byte ->
    (hash xor byte.toUByte().toULong()) * FNV_PRIME
}

private const val REQUEST_ID_HEADER = "X-Request-ID"
private const val FNV_OFFSET_BASIS: ULong = 14_695_981_039_346_656_037UL
private const val FNV_SECOND_OFFSET_BASIS: ULong = 7_806_847_283_389_282_549UL
private const val FNV_PRIME: ULong = 1_099_511_628_211UL
