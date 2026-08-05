package com.catchzoon.network.operator

import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkRequestException
import com.catchzoon.network.core.NetworkResponseSource
import com.catchzoon.network.core.NetworkResult

/** 成功时转换数据，失败信息保持不变。 */
public inline fun <T, R> NetworkResult<T>.mapData(transform: (T) -> R): NetworkResult<R> = when (this) {
    is NetworkResult.Success -> NetworkResult.Success(
        data = transform(data),
        statusCode = statusCode,
        headers = headers,
        requestId = requestId,
        durationMillis = durationMillis,
        attempt = attempt,
        source = source,
    )
    is NetworkResult.Failure -> this
}

/** 成功后继续组合另一个结果，失败时短路。 */
public inline fun <T, R> NetworkResult<T>.flatMapData(transform: (T) -> NetworkResult<R>): NetworkResult<R> = when (this) {
    is NetworkResult.Success -> transform(data)
    is NetworkResult.Failure -> this
}

/** 只转换失败信息，成功数据和元数据保持不变。 */
public inline fun <T> NetworkResult<T>.mapFailure(transform: (NetworkFailure) -> NetworkFailure): NetworkResult<T> =
    when (this) {
        is NetworkResult.Success -> this
        is NetworkResult.Failure -> NetworkResult.Failure(transform(error))
    }

/** 把失败恢复为本地数据。 */
public inline fun <T> NetworkResult<T>.recover(transform: (NetworkFailure) -> T): NetworkResult<T> = when (this) {
    is NetworkResult.Success -> this
    is NetworkResult.Failure -> NetworkResult.Success(
        data = transform(error),
        statusCode = error.statusCode ?: 0,
        headers = emptyMap(),
        requestId = error.requestId,
        attempt = error.attempt,
        source = NetworkResponseSource.LOCAL_FALLBACK,
    )
}

/** 成功时执行操作并继续返回当前结果。 */
public inline fun <T> NetworkResult<T>.onSuccess(action: (T) -> Unit): NetworkResult<T> = apply {
    if (this is NetworkResult.Success) action(data)
}

/** 失败时执行操作并继续返回当前结果。 */
public inline fun <T> NetworkResult<T>.onFailure(action: (NetworkFailure) -> Unit): NetworkResult<T> = apply {
    if (this is NetworkResult.Failure) action(error)
}

/** 成功时返回数据，失败时返回 null。 */
public fun <T> NetworkResult<T>.getOrNull(): T? = (this as? NetworkResult.Success)?.data

/** 成功时返回数据，失败时使用调用方提供的默认值。 */
public inline fun <T> NetworkResult<T>.getOrElse(defaultValue: (NetworkFailure) -> T): T = when (this) {
    is NetworkResult.Success -> data
    is NetworkResult.Failure -> defaultValue(error)
}

/** 成功时返回数据，失败时抛出携带结构化原因的异常。 */
public fun <T> NetworkResult<T>.getOrThrow(): T = when (this) {
    is NetworkResult.Success -> data
    is NetworkResult.Failure -> throw NetworkRequestException(error)
}

/** 把成功与失败统一折叠为一个业务类型。 */
public inline fun <T, R> NetworkResult<T>.fold(
    onSuccess: (NetworkResult.Success<T>) -> R,
    onFailure: (NetworkFailure) -> R,
): R = when (this) {
    is NetworkResult.Success -> onSuccess(this)
    is NetworkResult.Failure -> onFailure(error)
}
