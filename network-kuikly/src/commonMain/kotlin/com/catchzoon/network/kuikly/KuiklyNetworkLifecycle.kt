package com.catchzoon.network.kuikly

import com.catchzoon.network.core.NetworkCall
import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkResult
import com.catchzoon.network.core.NetworkState
import com.catchzoon.network.core.NetworkTlsPolicy
import com.tencent.kuikly.core.coroutines.Job
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.pager.Pager

/**
 * 保存单个 Kuikly 页面的网络任务和客户端。
 *
 * 页面在 `pageWillDestroy` 调用 [Pager.closeNetworkScope] 后，任务和底层请求会一起取消。
 */
public class KuiklyNetworkScope internal constructor(private val pager: Pager) {
    private val clients = mutableListOf<NetworkClient>()
    private val jobs = mutableListOf<Job>()
    private var closed = false

    internal fun register(client: NetworkClient): NetworkClient = client.also {
        check(!closed) { "Kuikly 网络作用域已经关闭" }
        clients += it
    }

    /** 在页面生命周期协程中执行请求，并统一输出 Loading、Success 和 Error。 */
    public fun <T> launch(
        call: NetworkCall<T>,
        onState: (NetworkState<T>) -> Unit,
    ): Job {
        check(!closed) { "Kuikly 网络作用域已经关闭" }
        onState(NetworkState.Loading)
        return pager.lifecycleScope.launch {
            when (val result = call.await()) {
                is NetworkResult.Success -> onState(
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
                is NetworkResult.Failure -> onState(NetworkState.Error(result.error))
            }
        }.also(jobs::add)
    }

    /** 关闭页面拥有的全部请求资源；重复调用安全。 */
    public fun close() {
        if (closed) return
        closed = true
        jobs.forEach(Job::cancel)
        clients.forEach(NetworkClient::cancelAll)
        jobs.clear()
        clients.clear()
    }
}

/** 返回页面唯一的网络作用域。 */
public val Pager.networkScope: KuiklyNetworkScope
    get() = (getValueForKey(SCOPE_KEY) as? KuiklyNetworkScope)
        ?: KuiklyNetworkScope(this).also { setMemoryCache(SCOPE_KEY, it) }

/** 页面销毁时调用，统一取消该页面发起的任务和平台请求。 */
public fun Pager.closeNetworkScope(): Unit = networkScope.close()

/** 创建与 Kuikly 页面绑定的公共客户端。 */
public fun createKuiklyNetworkClient(
    pager: Pager,
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy = NetworkTlsPolicy(allowCleartext = true),
    configure: NetworkClient.Builder.() -> Unit = {},
): NetworkClient = pager.networkScope.register(
    NetworkClient.Builder(createKuiklyScopedNetworkEngine(pager, baseUrl, tlsPolicy))
        .apply(configure)
        .build(),
)

/** 页面直接执行调用的便捷入口。 */
public fun <T> Pager.launchRequest(
    call: NetworkCall<T>,
    onSuccess: (T) -> Unit,
    onFailure: (NetworkFailure) -> Unit = {},
): Job = networkScope.launch(call) { state ->
    when (state) {
        is NetworkState.Success -> onSuccess(state.data)
        is NetworkState.Error -> onFailure(state.failure)
        NetworkState.Loading -> Unit
    }
}

private const val SCOPE_KEY = "com.catchzoon.network.kuikly.scope"
