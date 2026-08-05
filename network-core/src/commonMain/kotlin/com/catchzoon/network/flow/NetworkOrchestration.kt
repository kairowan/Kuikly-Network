package com.catchzoon.network.flow

import com.catchzoon.network.core.NetworkCall
import com.catchzoon.network.core.NetworkResult
import com.catchzoon.network.core.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 把冷请求状态流转换为页面可复用的单向 StateFlow。 */
public fun <T> NetworkCall<T>.asStateFlow(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
): StateFlow<NetworkState<T>> = asFlow().stateIn(scope, started, NetworkState.Loading)

/** 并行执行一组调用，同时限制并发数并保持输入顺序。 */
public suspend fun <T> Iterable<NetworkCall<T>>.awaitAllNetwork(
    maxConcurrency: Int = 8,
): List<NetworkResult<T>> = coroutineScope {
    require(maxConcurrency in 1..64) { "maxConcurrency 必须在 1..64 之间" }
    val semaphore = Semaphore(maxConcurrency)
    map { call -> async { semaphore.withPermit { call.await() } } }.map { it.await() }
}

/** 按固定大小拆分业务输入，再由调用方创建批量接口调用。 */
public suspend fun <Input, Output> Iterable<Input>.executeNetworkBatches(
    batchSize: Int,
    maxConcurrentBatches: Int = 2,
    createCall: (List<Input>) -> NetworkCall<Output>,
): List<NetworkResult<Output>> {
    require(batchSize in 1..1_000) { "batchSize 必须在 1..1000 之间" }
    return chunked(batchSize).map(createCall).awaitAllNetwork(maxConcurrentBatches)
}

/** 可取消的轮询参数。 */
public data class NetworkPollingPolicy(
    val intervalMillis: Long = 2_000L,
    val maxPolls: Int = 60,
    val stopOnFailure: Boolean = false,
) {
    init {
        require(intervalMillis in 100L..300_000L) { "轮询间隔必须在 100..300000 毫秒之间" }
        require(maxPolls in 1..10_000) { "maxPolls 必须在 1..10000 之间" }
    }
}

/**
 * 轮询网络调用并输出单向状态流。
 *
 * Flow 被取消后当前请求和后续 delay 会随协程一起取消，不需要额外计时器。
 */
public fun <T> pollNetwork(
    policy: NetworkPollingPolicy = NetworkPollingPolicy(),
    stopWhen: (T) -> Boolean,
    callFactory: () -> NetworkCall<T>,
): Flow<NetworkState<T>> = flow {
    emit(NetworkState.Loading)
    repeat(policy.maxPolls) { index ->
        when (val result = callFactory().await()) {
            is NetworkResult.Success -> {
                emit(
                    NetworkState.Success(
                        data = result.data,
                        statusCode = result.statusCode,
                        headers = result.headers,
                        requestId = result.requestId,
                        durationMillis = result.durationMillis,
                        attempt = result.attempt,
                        source = result.source,
                    ),
                )
                if (stopWhen(result.data)) return@flow
            }
            is NetworkResult.Failure -> {
                emit(NetworkState.Error(result.error))
                if (policy.stopOnFailure) return@flow
            }
        }
        if (index + 1 < policy.maxPolls) delay(policy.intervalMillis)
    }
}
