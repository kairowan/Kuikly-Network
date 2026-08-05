package com.catchzoon.network.testing

import com.catchzoon.network.core.NetworkMethod
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlin.test.assertTrue

class ScriptedNetworkEngineTest {
    @Test
    fun scenariosAreConsumedInOrderAndRequestsAreRecorded() = runBlocking {
        val engine = ScriptedNetworkEngine(listOf(respond("first"), respond("second", statusCode = 201)))
        val client = engine.client()

        val first = client.executeRaw(NetworkRawRequest("/v1/first", NetworkMethod.GET))
        val second = client.executeRaw(NetworkRawRequest("/v1/second", NetworkMethod.POST))

        assertEquals("first", assertIs<NetworkRawResult.Response>(first).value.body)
        assertEquals(201, assertIs<NetworkRawResult.Response>(second).value.statusCode)
        assertEquals(listOf("/v1/first", "/v1/second"), engine.recordedRequests().map { it.relativePath })
    }

    @Test
    fun fixturesAndStandardFailuresRemainDeterministic() = runBlocking {
        val engine = ScriptedNetworkEngine(
            listOf(NetworkFixture("{\"ok\":true}").scenario(), offline(), timeout()),
        )
        val client = engine.client()

        assertIs<NetworkRawResult.Response>(client.executeRaw(NetworkRawRequest("/ok", NetworkMethod.GET)))
        repeat(2) { index ->
            assertIs<NetworkRawResult.Failure>(
                client.executeRaw(NetworkRawRequest("/failure/$index", NetworkMethod.GET)),
            )
        }
        assertEquals(0, engine.remainingScenarios())
        assertTrue(engine.recordedRequests().size == 3)
    }
}
