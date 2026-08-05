package com.catchzoon.network.cache

import com.catchzoon.network.core.NetworkRawResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 有容量上限的进程内 LRU 缓存。
 *
 * 默认最多 128 条、8MiB；超大单条响应不会写入，避免缓存反而制造内存压力。
 */
public class MemoryNetworkCacheStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : NetworkCacheStore {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, SizedCacheEntry>()
    private var currentBytes = 0L

    init {
        require(maxEntries in 1..MAX_ENTRIES) { "maxEntries 必须在 1..$MAX_ENTRIES 之间" }
        require(maxBytes in MIN_CACHE_BYTES..MAX_CACHE_BYTES) { "maxBytes 超出允许范围" }
    }

    override suspend fun get(key: String): NetworkCacheEntry? = mutex.withLock {
        val value = entries.remove(key) ?: return@withLock null
        entries[key] = value
        value.entry
    }

    override suspend fun put(key: String, entry: NetworkCacheEntry) {
        val size = entry.response.estimatedBytes()
        mutex.withLock {
            entries.remove(key)?.let { currentBytes -= it.bytes }
            if (size > maxBytes) return@withLock
            while (entries.isNotEmpty() && (entries.size >= maxEntries || currentBytes + size > maxBytes)) {
                val oldestKey = entries.keys.first()
                currentBytes -= requireNotNull(entries.remove(oldestKey)).bytes
            }
            entries[key] = SizedCacheEntry(entry, size)
            currentBytes += size
        }
    }

    override suspend fun remove(key: String) {
        mutex.withLock {
            entries.remove(key)?.let { currentBytes -= it.bytes }
        }
    }

    override suspend fun removeByTags(tags: Set<String>) {
        if (tags.isEmpty()) return
        mutex.withLock {
            val keys = entries.filterValues { value -> value.entry.tags.any(tags::contains) }.keys.toList()
            keys.forEach { key -> entries.remove(key)?.let { currentBytes -= it.bytes } }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            entries.clear()
            currentBytes = 0L
        }
    }
}

private data class SizedCacheEntry(val entry: NetworkCacheEntry, val bytes: Long)

private fun NetworkRawResponse.estimatedBytes(): Long = (bodyBytes?.size ?: body.encodeToByteArray().size).toLong() +
    headers.entries.sumOf { (name, value) -> name.length.toLong() + value.length }

private const val DEFAULT_MAX_ENTRIES = 128
private const val MAX_ENTRIES = 1_024
private const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L
private const val MIN_CACHE_BYTES = 64L * 1024L
private const val MAX_CACHE_BYTES = 64L * 1024L * 1024L
