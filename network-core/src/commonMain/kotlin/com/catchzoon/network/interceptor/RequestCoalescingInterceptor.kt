package com.catchzoon.network.interceptor

import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkMethod
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.stableIdentityKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 合并同一客户端内同时发生的相同 GET 请求，避免页面并发初始化时重复消耗流量和服务端配额。
 *
 * 每个调用方只取消自己的等待；最后一个等待者离开时才取消真实请求。请求头会参与键计算，
 * 不同用户或不同鉴权状态的请求不会错误复用。
 */
public class RequestCoalescingInterceptor(
    private val shouldCoalesce: (NetworkRawRequest) -> Boolean = { it.method == NetworkMethod.GET },
) : NetworkInterceptor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, SharedRequest>()

    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        val request = chain.request
        if (!shouldCoalesce(request)) return chain.proceed()
        val key = request.stableIdentityKey(includeTransportPolicy = true)
        val shared = acquire(key, chain)
        return try {
            shared.response.await()
        } finally {
            withContext(NonCancellable) { release(key, shared) }
        }
    }

    override fun cancelAll() {
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun acquire(key: String, chain: NetworkInterceptor.Chain): SharedRequest {
        val shared = mutex.withLock {
            inFlight[key]
                ?.takeUnless { it.response.isCancelled }
                ?.also { it.waiterCount++ }
                ?: SharedRequest(
                    response = scope.async(start = CoroutineStart.LAZY) { chain.proceed() },
                ).also { inFlight[key] = it }
        }
        shared.response.start()
        return shared
    }

    private suspend fun release(key: String, shared: SharedRequest) {
        val shouldCancel = mutex.withLock {
            if (inFlight[key] !== shared) return@withLock false
            shared.waiterCount--
            if (shared.waiterCount > 0) return@withLock false
            inFlight.remove(key)
            shared.response.isActive
        }
        if (shouldCancel) shared.response.cancel()
    }
}

private class SharedRequest(
    val response: Deferred<NetworkRawResponse>,
    var waiterCount: Int = 1,
)
