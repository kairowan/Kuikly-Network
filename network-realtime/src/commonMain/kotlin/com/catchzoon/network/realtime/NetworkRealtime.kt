package com.catchzoon.network.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** SSE 标准事件。 */
public data class ServerSentEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
    val retryMillis: Long? = null,
)

/**
 * 增量解析 SSE 文本块；支持网络分片落在任意字符边界。
 *
 * Transport 只需提供文本块 Flow，协议解析不绑定 Ktor、OkHttp 或平台 API。
 */
public fun parseServerSentEvents(chunks: Flow<String>): Flow<ServerSentEvent> = flow {
    var pending = ""
    val eventLines = mutableListOf<String>()
    suspend fun emitEvent() {
        if (eventLines.isEmpty()) return
        val data = eventLines.filter { it.startsWith("data:") }.joinToString("\n") { it.substringAfter(':').trimStart() }
        if (data.isNotEmpty()) {
            emit(
                ServerSentEvent(
                    data = data,
                    event = eventLines.firstOrNull { it.startsWith("event:") }?.substringAfter(':')?.trim(),
                    id = eventLines.firstOrNull { it.startsWith("id:") }?.substringAfter(':')?.trim(),
                    retryMillis = eventLines.firstOrNull { it.startsWith("retry:") }
                        ?.substringAfter(':')?.trim()?.toLongOrNull(),
                ),
            )
        }
        eventLines.clear()
    }
    chunks.collect { chunk ->
        pending += chunk.replace("\r\n", "\n")
        while ('\n' in pending) {
            val line = pending.substringBefore('\n')
            pending = pending.substringAfter('\n')
            when {
                line.isEmpty() -> emitEvent()
                line.startsWith(':') -> Unit
                else -> eventLines += line
            }
        }
    }
    if (pending.isNotEmpty()) eventLines += pending
    emitEvent()
}
