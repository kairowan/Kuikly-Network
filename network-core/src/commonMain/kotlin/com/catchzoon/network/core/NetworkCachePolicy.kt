package com.catchzoon.network.core

/** GET 响应缓存策略；默认关闭，避免未声明的数据被意外缓存。 */
public data class NetworkCachePolicy(
    val maxAgeSeconds: Int,
    val staleIfErrorSeconds: Int = 0,
    val mode: NetworkCacheMode = if (maxAgeSeconds > 0) NetworkCacheMode.CACHE_FIRST else NetworkCacheMode.NETWORK_ONLY,
    val staleWhileRevalidateSeconds: Int = 0,
) {
    init {
        require(maxAgeSeconds in 0..MAX_CACHE_AGE_SECONDS) { "maxAgeSeconds 超出允许范围" }
        require(staleIfErrorSeconds in 0..MAX_STALE_AGE_SECONDS) { "staleIfErrorSeconds 超出允许范围" }
        require(staleWhileRevalidateSeconds in 0..MAX_STALE_AGE_SECONDS) {
            "staleWhileRevalidateSeconds 超出允许范围"
        }
    }

    public val enabled: Boolean get() = maxAgeSeconds > 0

    public companion object {
        /** 不读写响应缓存。 */
        public fun none(): NetworkCachePolicy = NetworkCachePolicy(maxAgeSeconds = 0)

        /** 在有效期内优先返回缓存。 */
        public fun cacheFirst(maxAgeSeconds: Int): NetworkCachePolicy = NetworkCachePolicy(maxAgeSeconds)

        /** 在线请求失败时允许返回指定时长内的过期缓存。 */
        public fun staleIfError(maxAgeSeconds: Int, staleIfErrorSeconds: Int): NetworkCachePolicy =
            NetworkCachePolicy(maxAgeSeconds, staleIfErrorSeconds)

        /** 优先请求网络，失败后读取允许范围内的缓存。 */
        public fun networkFirst(maxAgeSeconds: Int, staleIfErrorSeconds: Int = 0): NetworkCachePolicy =
            NetworkCachePolicy(maxAgeSeconds, staleIfErrorSeconds, NetworkCacheMode.NETWORK_FIRST)

        /** 立即返回缓存，同时在后台刷新。 */
        public fun staleWhileRevalidate(maxAgeSeconds: Int, staleWhileRevalidateSeconds: Int): NetworkCachePolicy =
            NetworkCachePolicy(
                maxAgeSeconds = maxAgeSeconds,
                mode = NetworkCacheMode.STALE_WHILE_REVALIDATE,
                staleWhileRevalidateSeconds = staleWhileRevalidateSeconds,
            )

        /** 只读取缓存，不触发网络。 */
        public fun cacheOnly(maxAgeSeconds: Int): NetworkCachePolicy =
            NetworkCachePolicy(maxAgeSeconds, mode = NetworkCacheMode.CACHE_ONLY)
    }
}

public enum class NetworkCacheMode { NETWORK_ONLY, CACHE_FIRST, NETWORK_FIRST, CACHE_ONLY, STALE_WHILE_REVALIDATE }

private const val MAX_CACHE_AGE_SECONDS = 24 * 60 * 60
private const val MAX_STALE_AGE_SECONDS = 7 * 24 * 60 * 60
