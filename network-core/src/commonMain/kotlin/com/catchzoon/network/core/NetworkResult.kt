package com.catchzoon.network.core

/** 原始请求结果，供需要自行解释服务端错误体的二次封装使用。 */
public sealed interface NetworkRawResult {
    public data class Response(val value: NetworkRawResponse) : NetworkRawResult
    public data class Failure(val value: NetworkFailure) : NetworkRawResult
}

/** 类型化请求结果，不向调用方抛出普通网络或解析异常。 */
public sealed interface NetworkResult<out T> {
    public data class Success<T>(
        val data: T,
        val statusCode: Int,
        val headers: Map<String, String>,
        val requestId: String = "",
        val durationMillis: Long = 0L,
        val attempt: Int = 1,
        val source: NetworkResponseSource = NetworkResponseSource.NETWORK,
    ) : NetworkResult<T>

    public data class Failure(val error: NetworkFailure) : NetworkResult<Nothing>
}

/** 页面可直接收集的单向状态流。 */
public sealed interface NetworkState<out T> {
    public data object Loading : NetworkState<Nothing>
    public data class Success<T>(
        val data: T,
        val statusCode: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val requestId: String = "",
        val durationMillis: Long = 0L,
        val attempt: Int = 1,
        val source: NetworkResponseSource = NetworkResponseSource.NETWORK,
    ) : NetworkState<T>
    public data class Error(val failure: NetworkFailure) : NetworkState<Nothing>
}
