package com.catchzoon.network.cache

import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.MonotonicNetworkClock
import com.catchzoon.network.core.NetworkClock
import com.catchzoon.network.core.NetworkCachePolicy
import com.catchzoon.network.core.NetworkCacheMode
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkMethod
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkResponseSource
import com.catchzoon.network.core.NetworkTransportException
import com.catchzoon.network.core.stableIdentityKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/** 按单次请求策略读写缓存，并在明确允许时使用过期数据兜底。 */
public class NetworkResponseCacheInterceptor(
    private val store: NetworkCacheStore,
    private val clock: NetworkClock = MonotonicNetworkClock,
) : NetworkInterceptor {
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        val request = chain.request
        if (request.method != NetworkMethod.GET) {
            val response = chain.proceed()
            if (response.statusCode in 200..299 && request.invalidateCacheTags.isNotEmpty()) {
                store.removeByTagsSafely(request.invalidateCacheTags)
            }
            return response
        }
        val policy = request.cachePolicy
        if (!policy.enabled) return chain.proceed()
        val key = request.stableIdentityKey(includeTransportPolicy = false)
        val cached = store.getSafely(key)
        val ageMillis = cached?.let { (clock.nowMillis() - it.storedAtMillis).coerceAtLeast(0L) }
        val fresh = cached != null && ageMillis != null && ageMillis <= policy.maxAgeSeconds * 1_000L
        if (policy.mode == NetworkCacheMode.CACHE_ONLY) {
            return if (fresh) cached!!.asCachedResponse() else throw cacheMiss()
        }
        if (policy.mode == NetworkCacheMode.STALE_WHILE_REVALIDATE && cached != null && ageMillis != null &&
            ageMillis <= (policy.maxAgeSeconds.toLong() + policy.staleWhileRevalidateSeconds) * 1_000L
        ) {
            refreshScope.launch { refreshSafely(chain, request, key) }
            return cached.asCachedResponse()
        }
        if (policy.mode == NetworkCacheMode.CACHE_FIRST && fresh) {
            return cached.response.copy(durationMillis = 0L, source = NetworkResponseSource.MEMORY_CACHE)
        }

        val response = try {
            chain.proceed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return cached.staleResponseOrThrow(ageMillis, policy, failure)
        }
        if (response.statusCode in 200..299) {
            store.putSafely(
                key,
                NetworkCacheEntry(
                    response = response.copy(durationMillis = 0L, source = NetworkResponseSource.NETWORK),
                    storedAtMillis = clock.nowMillis(),
                    tags = request.cacheTags,
                ),
            )
            return response
        }
        return if (response.statusCode == 408 || response.statusCode == 429 || response.statusCode >= 500) {
            cached.staleResponseOrNull(ageMillis, policy) ?: response
        } else {
            response
        }
    }

    override fun cancelAll() {
        refreshScope.coroutineContext.cancelChildren()
    }

    private suspend fun refreshSafely(
        chain: NetworkInterceptor.Chain,
        request: NetworkRawRequest,
        key: String,
    ) {
        try {
            val response = chain.proceed()
            if (response.statusCode in 200..299) {
                store.putSafely(
                    key,
                    NetworkCacheEntry(
                        response.copy(durationMillis = 0L, source = NetworkResponseSource.NETWORK),
                        clock.nowMillis(),
                        request.cacheTags,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 后台刷新失败时继续保留已有缓存。
        }
    }
}

private fun NetworkCacheEntry?.staleResponseOrNull(
    ageMillis: Long?,
    policy: NetworkCachePolicy,
): NetworkRawResponse? {
    if (this == null || ageMillis == null || policy.staleIfErrorSeconds <= 0) return null
    val allowedAge = (policy.maxAgeSeconds.toLong() + policy.staleIfErrorSeconds) * 1_000L
    return response.takeIf { ageMillis <= allowedAge }
        ?.copy(durationMillis = 0L, source = NetworkResponseSource.STALE_CACHE)
}

private fun NetworkCacheEntry.asCachedResponse(): NetworkRawResponse =
    response.copy(durationMillis = 0L, source = NetworkResponseSource.MEMORY_CACHE)

private fun cacheMiss(): NetworkTransportException = NetworkTransportException(
    NetworkFailure(
        code = "cache_miss",
        category = NetworkFailureCategory.CONNECTIVITY,
        message = "本地没有可用缓存",
    ),
)

private fun NetworkCacheEntry?.staleResponseOrThrow(
    ageMillis: Long?,
    policy: NetworkCachePolicy,
    failure: Exception,
): NetworkRawResponse = staleResponseOrNull(ageMillis, policy) ?: throw failure

private suspend fun NetworkCacheStore.getSafely(key: String): NetworkCacheEntry? = try {
    get(key)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private suspend fun NetworkCacheStore.putSafely(key: String, entry: NetworkCacheEntry) {
    try {
        put(key, entry)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // 缓存是旁路能力，写入失败不能改变在线请求结果。
    }
}

private suspend fun NetworkCacheStore.removeByTagsSafely(tags: Set<String>) {
    try {
        removeByTags(tags)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // 缓存失效是旁路能力，失败不能改变写请求结果。
    }
}
