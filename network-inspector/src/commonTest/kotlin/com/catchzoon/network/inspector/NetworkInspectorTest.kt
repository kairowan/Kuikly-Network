package com.catchzoon.network.inspector

import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkMethod
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkRawResult
import com.catchzoon.network.core.NetworkTransportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class NetworkInspectorTest {
    @Test
    fun inspectorRedactsSecretsAndKeepsBoundedHistory() = runBlocking {
        val inspector = NetworkInspector(maxEntries = 1)
        val client = NetworkClient.Builder(SuccessEngine()).inspector(inspector).build()

        repeat(2) {
            assertIs<NetworkRawResult.Response>(
                client.executeRaw(
                    NetworkRawRequest(
                        relativePath = "/v1/items?token=secret&safe=yes",
                        method = NetworkMethod.GET,
                        headers = mapOf("Authorization" to "Bearer private"),
                    ),
                ),
            )
        }

        val snapshot = inspector.exchanges.value.single()
        assertEquals("██", snapshot.requestHeaders["Authorization"])
        assertFalse(snapshot.path.contains("secret"))
        assertEquals(200, snapshot.statusCode)
    }

    @Test
    fun inspectorKeepsStructuredTransportFailure() = runBlocking {
        val inspector = NetworkInspector()
        val client = NetworkClient.Builder(FailureEngine()).inspector(inspector).build()

        assertIs<NetworkRawResult.Failure>(
            client.executeRaw(NetworkRawRequest(relativePath = "/v1/items", method = NetworkMethod.GET)),
        )

        assertEquals("offline", inspector.exchanges.value.single().failure?.code)
    }

    private class SuccessEngine : NetworkEngine {
        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse = NetworkRawResponse(200, "{}")
        override fun cancelAll() = Unit
    }

    private class FailureEngine : NetworkEngine {
        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse = throw NetworkTransportException(
            NetworkFailure(
                code = "offline",
                category = NetworkFailureCategory.CONNECTIVITY,
                retryable = true,
            ),
        )

        override fun cancelAll() = Unit
    }
}
