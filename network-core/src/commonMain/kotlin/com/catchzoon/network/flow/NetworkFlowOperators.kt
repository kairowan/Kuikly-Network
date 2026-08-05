package com.catchzoon.network.flow

import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkResponseSource
import com.catchzoon.network.core.NetworkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.CoroutineContext

/** 只转换成功数据，Loading 和 Error 状态保持原有方向继续下发。 */
public fun <T, R> Flow<NetworkState<T>>.mapData(transform: suspend (T) -> R): Flow<NetworkState<R>> = map { state ->
    when (state) {
        NetworkState.Loading -> NetworkState.Loading
        is NetworkState.Success -> NetworkState.Success(
            data = transform(state.data),
            statusCode = state.statusCode,
            headers = state.headers,
            requestId = state.requestId,
            durationMillis = state.durationMillis,
            attempt = state.attempt,
            source = state.source,
        )
        is NetworkState.Error -> state
    }
}

/** 在不改变数据流的情况下监听成功数据。 */
public fun <T> Flow<NetworkState<T>>.onData(action: suspend (T) -> Unit): Flow<NetworkState<T>> = onEach { state ->
    if (state is NetworkState.Success) action(state.data)
}

/** 在不改变数据流的情况下监听 Loading。 */
public fun <T> Flow<NetworkState<T>>.onLoading(action: suspend () -> Unit): Flow<NetworkState<T>> = onEach { state ->
    if (state === NetworkState.Loading) action()
}

/** 在不吞掉错误状态的情况下监听失败。 */
public fun <T> Flow<NetworkState<T>>.onFailure(action: suspend (NetworkFailure) -> Unit): Flow<NetworkState<T>> =
    onEach { state -> if (state is NetworkState.Error) action(state.failure) }

/** 转换错误状态，Loading 和成功数据保持原样。 */
public fun <T> Flow<NetworkState<T>>.mapFailure(
    transform: suspend (NetworkFailure) -> NetworkFailure,
): Flow<NetworkState<T>> = map { state ->
    if (state is NetworkState.Error) NetworkState.Error(transform(state.failure)) else state
}

/** 使用本地数据恢复错误状态，适合明确允许降级的页面。 */
public fun <T> Flow<NetworkState<T>>.recoverData(
    transform: suspend (NetworkFailure) -> T?,
): Flow<NetworkState<T>> = map { state ->
    if (state !is NetworkState.Error) return@map state
    transform(state.failure)?.let { data ->
        NetworkState.Success(
            data = data,
            statusCode = state.failure.statusCode ?: 0,
            requestId = state.failure.requestId,
            attempt = state.failure.attempt,
            source = NetworkResponseSource.LOCAL_FALLBACK,
        )
    } ?: state
}

/** 切换上游请求、解析和操作符执行上下文，语义与 Flow.flowOn 一致。 */
public fun <T> Flow<T>.executeOn(context: CoroutineContext): Flow<T> = flowOn(context)
