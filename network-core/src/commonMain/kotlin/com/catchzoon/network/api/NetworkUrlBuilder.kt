package com.catchzoon.network.api

import com.catchzoon.network.core.isSafeRelativeNetworkPath

/**
 * 安全构建相对接口地址，路径段和查询参数会按 UTF-8 百分号编码。
 *
 * 业务不应直接把用户输入拼进 URL；固定路径仍可以直接写字符串。
 */
public class NetworkUrlBuilder internal constructor(basePath: String) {
    private val path = StringBuilder(basePath.trimEnd('/'))
    private val queries = mutableListOf<Pair<String, String>>()

    init {
        require(!basePath.contains('?') && isSafeRelativeNetworkPath(basePath)) { "基础接口路径无效" }
    }

    /** 追加一个不可为空的动态路径段。 */
    public fun segment(value: String): NetworkUrlBuilder = apply {
        require(value.isNotEmpty() && value.length <= MAX_COMPONENT_LENGTH) { "路径参数无效" }
        path.append('/').append(value.percentEncode())
    }

    /** 追加查询参数；值为 null 时忽略该参数。 */
    public fun query(name: String, value: String?): NetworkUrlBuilder = apply {
        require(name.isNotEmpty() && name.length <= MAX_COMPONENT_LENGTH) { "查询参数名称无效" }
        if (value != null) {
            require(value.length <= MAX_COMPONENT_LENGTH) { "查询参数值过长" }
            queries += name.percentEncode() to value.percentEncode()
        }
    }

    /** 返回只包含相对路径的最终地址。 */
    public fun build(): String = buildString {
        append(path)
        queries.forEachIndexed { index, (name, value) ->
            append(if (index == 0) '?' else '&')
            append(name).append('=').append(value)
        }
    }
}

/** 使用 DSL 构建安全相对地址。 */
public fun networkUrl(basePath: String, block: NetworkUrlBuilder.() -> Unit = {}): String =
    NetworkUrlBuilder(basePath).apply(block).build()

/** KSP 生成代码使用的注解路径解析入口。 */
public fun resolveAnnotatedNetworkUrl(
    template: String,
    pathValues: Map<String, String>,
    queryValues: List<Pair<String, String?>>,
    allowAbsoluteUrl: Boolean = false,
): String {
    val parameters = ANNOTATED_ROUTE_PARAMETER.findAll(template).map { it.groupValues[1] }.toSet()
    require(parameters == pathValues.keys) { "接口路径参数声明和调用参数不一致" }
    var resolved = template
    pathValues.forEach { (name, value) ->
        require(value.isNotEmpty()) { "路径参数 $name 不能为空" }
        resolved = resolved.replace("{$name}", encodeNetworkComponent(value))
    }
    val query = queryValues.mapNotNull { (name, value) ->
        require(name.isNotEmpty() && name.length <= MAX_COMPONENT_LENGTH) { "查询参数名称无效" }
        value?.let {
            require(it.length <= MAX_COMPONENT_LENGTH) { "查询参数值过长" }
            encodeNetworkComponent(name) to encodeNetworkComponent(it)
        }
    }
    if (query.isNotEmpty()) {
        resolved += query.joinToString(prefix = "?", separator = "&") { (name, value) -> "$name=$value" }
    }
    require(
        isSafeRelativeNetworkPath(resolved) ||
            allowAbsoluteUrl && com.catchzoon.network.core.isSafeAbsoluteNetworkUrl(resolved),
    ) { "生成的接口路径无效" }
    return resolved
}

internal fun encodeNetworkComponent(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xFF
        val character = value.toChar()
        if (character.isUnreservedUrlCharacter()) {
            append(character)
        } else {
            append('%')
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
}

private fun String.percentEncode(): String = encodeNetworkComponent(this)

private fun Char.isUnreservedUrlCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in "-._~"

private const val HEX = "0123456789ABCDEF"
private const val MAX_COMPONENT_LENGTH = 1_024
private val ANNOTATED_ROUTE_PARAMETER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")
