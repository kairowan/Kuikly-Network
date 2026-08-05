package com.catchzoon.network.interceptor

import com.catchzoon.network.cache.MemoryNetworkCacheStore
import com.catchzoon.network.cache.NetworkCacheStore
import com.catchzoon.network.cache.NetworkResponseCacheInterceptor
import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkClock
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.resilience.NetworkCircuitBreakerInterceptor
import com.catchzoon.network.resilience.NetworkCircuitBreakerPolicy
import com.catchzoon.network.resilience.NetworkConcurrencyInterceptor
import com.catchzoon.network.resilience.NetworkConcurrencyPolicy
import com.catchzoon.network.resilience.NetworkConnectivityInterceptor
import com.catchzoon.network.resilience.NetworkConnectivityProvider
import com.catchzoon.network.resilience.NetworkPriorityInterceptor
import com.catchzoon.network.resilience.NetworkPriorityPolicy
import com.catchzoon.network.resilience.NetworkRateLimitInterceptor
import com.catchzoon.network.resilience.NetworkRateLimitPolicy
import com.catchzoon.network.monitor.NetworkMetricsCollector

/** 一步配置动态公共请求头。 */
public fun NetworkClient.Builder.commonHeaders(
    provider: suspend (NetworkRawRequest) -> Map<String, String>,
): NetworkClient.Builder = addInterceptor(CommonHeadersInterceptor(provider))

/** 一步配置可自动刷新的 Bearer 会话。 */
public fun NetworkClient.Builder.bearerAuthentication(
    currentToken: suspend () -> String?,
    refreshToken: suspend (expiredToken: String?) -> String?,
    shouldAuthenticate: (NetworkRawRequest) -> Boolean = { true },
): NetworkClient.Builder = addInterceptor(
    RefreshingBearerAuthInterceptor(currentToken, refreshToken, shouldAuthenticate),
)

/** 合并同一客户端内并发发生的相同 GET 请求。 */
public fun NetworkClient.Builder.coalesceRequests(
    shouldCoalesce: (NetworkRawRequest) -> Boolean = { it.method == com.catchzoon.network.core.NetworkMethod.GET },
): NetworkClient.Builder = addInterceptor(RequestCoalescingInterceptor(shouldCoalesce))

/** 配置有容量上限的响应缓存；可传入业务自己的加密磁盘实现。 */
public fun NetworkClient.Builder.responseCache(
    store: NetworkCacheStore = MemoryNetworkCacheStore(),
    clock: NetworkClock? = null,
): NetworkClient.Builder = addInterceptor(
    if (clock == null) NetworkResponseCacheInterceptor(store) else NetworkResponseCacheInterceptor(store, clock),
)

/** 限制真实在途请求数量，并为过载提供有上限的等待时间。 */
public fun NetworkClient.Builder.limitConcurrency(
    maxConcurrentRequests: Int,
    maxQueueWaitMillis: Long = 1_000L,
): NetworkClient.Builder = addInterceptor(
    NetworkConcurrencyInterceptor(NetworkConcurrencyPolicy(maxConcurrentRequests, maxQueueWaitMillis)),
)

/** 配置按接口分组的熔断器，避免故障期间持续压垮服务端。 */
public fun NetworkClient.Builder.circuitBreaker(
    policy: NetworkCircuitBreakerPolicy = NetworkCircuitBreakerPolicy(),
    clock: NetworkClock? = null,
    keySelector: (NetworkRawRequest) -> String = NetworkCircuitBreakerInterceptor::defaultKey,
): NetworkClient.Builder = addInterceptor(
    if (clock == null) {
        NetworkCircuitBreakerInterceptor(policy, keySelector = keySelector)
    } else {
        NetworkCircuitBreakerInterceptor(policy, clock, keySelector)
    },
)

/** 注册可通过 StateFlow 收集的脱敏滚动网络指标。 */
public fun NetworkClient.Builder.metrics(
    collector: NetworkMetricsCollector,
): NetworkClient.Builder = addEventListener(collector)

/** 系统确认离线时快速失败。 */
public fun NetworkClient.Builder.connectivity(
    provider: NetworkConnectivityProvider,
): NetworkClient.Builder = addInterceptor(NetworkConnectivityInterceptor(provider))

/** 按接口组配置客户端令牌桶限流。 */
public fun NetworkClient.Builder.rateLimit(
    policy: NetworkRateLimitPolicy = NetworkRateLimitPolicy(),
): NetworkClient.Builder = addInterceptor(NetworkRateLimitInterceptor(policy))

/** 使用有界优先级队列代替普通并发信号量。 */
public fun NetworkClient.Builder.priorityQueue(
    policy: NetworkPriorityPolicy = NetworkPriorityPolicy(),
): NetworkClient.Builder = addInterceptor(NetworkPriorityInterceptor(policy))

/** 配置跨平台 Cookie 管理。 */
public fun NetworkClient.Builder.cookies(
    store: NetworkCookieStore = MemoryNetworkCookieStore(),
): NetworkClient.Builder = addInterceptor(NetworkCookieInterceptor(store))

/** 配置默认 Accept 内容类型。 */
public fun NetworkClient.Builder.contentNegotiation(
    vararg acceptedContentTypes: String,
): NetworkClient.Builder = addInterceptor(
    NetworkContentNegotiationInterceptor(
        acceptedContentTypes.toList().ifEmpty { listOf("application/json") },
    ),
)
