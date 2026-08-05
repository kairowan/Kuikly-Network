package com.catchzoon.network.core

/** 仅允许相对路径，防止调用端绕过统一的服务地址和安全策略。 */
public fun isSafeRelativeNetworkPath(value: String): Boolean =
    value.startsWith('/') && value.length in 2..MAX_PATH_LENGTH &&
        !value.contains("://") && !value.contains("..") &&
        !value.contains('\\') && !value.contains('#') && value.none { it.code < 32 }

/** 动态绝对地址只允许 HTTPS，且禁止用户信息、片段和控制字符。 */
public fun isSafeAbsoluteNetworkUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) && value.length in 9..MAX_ABSOLUTE_URL_LENGTH &&
        value.substringAfter("https://", "").substringBefore('/').let { authority ->
            authority.isNotBlank() && '@' !in authority && authority.none { it.code < 32 }
        } && '#' !in value && value.none { it.code < 32 }

private const val MAX_PATH_LENGTH = 2_048
private const val MAX_ABSOLUTE_URL_LENGTH = 8_192
