package com.catchzoon.network.core

/** 经过公共层归一化、可被业务稳定处理的网络失败。 */
public data class NetworkFailure(
    val code: String,
    val category: NetworkFailureCategory = NetworkFailureCategory.UNKNOWN,
    val message: String = "",
    val statusCode: Int? = null,
    val retryable: Boolean = false,
    val requestId: String = "",
    val attempt: Int = 1,
    val retryAfterMillis: Long = 0L,
)

/** 失败分类用于稳定分支，业务代码不需要匹配平台异常文本。 */
public enum class NetworkFailureCategory {
    VALIDATION,
    SERIALIZATION,
    CONNECTIVITY,
    DNS,
    TLS,
    TIMEOUT,
    CANCELLED,
    CIRCUIT_OPEN,
    CLIENT_THROTTLED,
    HTTP,
    RESPONSE_TOO_LARGE,
    UNKNOWN,
}

/** 平台引擎使用的结构化异常，避免公共层依赖异常 message 判断类型。 */
public class NetworkTransportException(public val failure: NetworkFailure) : Exception(failure.message)

/** suspend 接口直接返回 DTO 时使用的结构化异常。 */
public class NetworkRequestException(public val failure: NetworkFailure) : Exception(failure.message.ifEmpty { failure.code })
