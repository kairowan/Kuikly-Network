package com.catchzoon.network.cache

import com.catchzoon.network.core.NetworkRawResponse

/** 可由业务替换为加密磁盘实现的原始响应缓存协议；持久化实现应再次散列传入的键。 */
public interface NetworkCacheStore {
    public suspend fun get(key: String): NetworkCacheEntry?
    public suspend fun put(key: String, entry: NetworkCacheEntry)
    public suspend fun remove(key: String) { clear() }
    public suspend fun removeByTags(tags: Set<String>) { if (tags.isNotEmpty()) clear() }
    public suspend fun clear()
}

/** 平台持久化缓存必须声明是否已加密，生产环境可据此拒绝明文实现。 */
public interface NetworkPersistentCacheStore : NetworkCacheStore {
    public val encryptedAtRest: Boolean
}

/** 缓存记录不包含请求正文；缓存键已经隔离鉴权和语言等请求头。 */
public data class NetworkCacheEntry(
    val response: NetworkRawResponse,
    val storedAtMillis: Long,
    val tags: Set<String> = emptySet(),
)
