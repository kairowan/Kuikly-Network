package com.catchzoon.network.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class NetworkRealtimeTest {
    @Test
    fun sseParserHandlesArbitraryChunkBoundaries() = runBlocking {
        val events = parseServerSentEvents(
            flowOf("id: 7\ndata: hel", "lo\ndata: world\n", "\nevent: done\ndata: ok\n\n"),
        ).toList()

        assertEquals(ServerSentEvent(data = "hello\nworld", id = "7"), events[0])
        assertEquals(ServerSentEvent(data = "ok", event = "done"), events[1])
    }
}
