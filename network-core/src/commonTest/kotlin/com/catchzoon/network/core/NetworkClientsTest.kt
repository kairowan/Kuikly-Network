package com.catchzoon.network.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NetworkClientsTest {
    @Test
    fun applicationRegistryResolvesDefaultAndNamedClients() {
        NetworkClients.shutdown()
        val defaultEngine = RegistryEngine()
        val uploadEngine = RegistryEngine()
        val defaultClient = NetworkClient.Builder(defaultEngine).build()
        val uploadClient = NetworkClient.Builder(uploadEngine).build()

        try {
            assertFailsWith<IllegalArgumentException> {
                NetworkClients.initialize(defaultClient, mapOf("上传" to uploadClient))
            }
            NetworkClients.initialize(defaultClient, mapOf("upload" to uploadClient))

            assertSame(defaultClient, NetworkClients.client())
            assertSame(uploadClient, NetworkClients.client("upload"))
            assertEquals(setOf("default", "upload"), NetworkClients.names)
            assertFailsWith<IllegalStateException> { NetworkClients.client("missing") }
        } finally {
            NetworkClients.shutdown()
        }

        assertEquals(1, defaultEngine.cancelCount)
        assertEquals(1, uploadEngine.cancelCount)
    }
}

private class RegistryEngine : NetworkEngine {
    var cancelCount: Int = 0

    override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse =
        NetworkRawResponse(statusCode = 200, body = "{}")

    override fun cancelAll() {
        cancelCount++
    }
}
