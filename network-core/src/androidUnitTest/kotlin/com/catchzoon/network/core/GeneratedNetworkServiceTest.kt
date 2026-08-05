package com.catchzoon.network.core

import com.catchzoon.network.annotation.Body
import com.catchzoon.network.annotation.Field
import com.catchzoon.network.annotation.FieldMap
import com.catchzoon.network.annotation.FormUrlEncoded
import com.catchzoon.network.annotation.GET
import com.catchzoon.network.annotation.Multipart
import com.catchzoon.network.annotation.NetworkSerialization
import com.catchzoon.network.annotation.NetworkService
import com.catchzoon.network.annotation.Part
import com.catchzoon.network.annotation.POST
import com.catchzoon.network.annotation.Streaming
import com.catchzoon.network.annotation.Url
import com.catchzoon.network.interceptor.coalesceRequests
import com.catchzoon.network.interceptor.limitConcurrency
import com.catchzoon.network.platform.RetrofitNetworkEngine
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/** 验证注解接口能够在 Android 目标生成并执行，无需手写实现类。 */
class GeneratedNetworkServiceTest {
    @Test
    fun generatedAnnotationApiNeedsNoManualImplementation() {
        val engine = RecordingEngine()
        val api = NetworkClient.Builder(engine).build().createGeneratedPingApi()

        val result = runImmediate { api.detail("a/b 中文", null) }

        assertEquals("pong", assertIs<NetworkResult.Success<PingResponse>>(result).data.value)
        assertEquals("/v1/ping/a%2Fb%20%E4%B8%AD%E6%96%87", engine.lastRequest?.relativePath)
        assertEquals("application/json", engine.lastRequest?.headers?.get("Content-Type"))

        val echo = runImmediate { api.echo(EchoRequest("hello"), "request-123").await() }
        assertEquals("pong", assertIs<NetworkResult.Success<PingResponse>>(echo).data.value)
        assertEquals(NetworkMethod.POST, engine.lastRequest?.method)
        assertEquals("{\"value\":\"hello\"}", engine.lastRequest?.body)
        assertEquals("request-123", engine.lastRequest?.headers?.get("Idempotency-Key"))

        assertEquals("pong", runImmediate(api::direct).value)
        val states = runImmediate { api.observe().toList() }
        assertIs<NetworkState.Loading>(states.first())
        assertEquals("pong", assertIs<NetworkState.Success<PingResponse>>(states.last()).data.value)
    }

    @Test
    fun generatedApiSupportsParameterMapsAndRequestTracing() {
        val engine = RecordingEngine()
        val result = runImmediate {
            NetworkClient.Builder(engine).build().createGeneratedPingApi().search(
                id = "a/b",
                queries = linkedMapOf("keyword" to "鸟 类", "ignored" to null),
                headers = mapOf("X-Client" to "mobile"),
                requestId = "request-42",
            ).await()
        }

        assertIs<NetworkResult.Success<PingResponse>>(result)
        assertEquals("/v1/ping/a%2Fb?keyword=%E9%B8%9F%20%E7%B1%BB", engine.lastRequest?.relativePath)
        assertEquals("mobile", engine.lastRequest?.headers?.get("X-Client"))
        assertEquals("request-42", engine.lastRequest?.headers?.get("X-Request-ID"))
        assertEquals(NetworkCachePolicy(60, 300), engine.lastRequest?.cachePolicy)
    }

    @Test
    fun androidApiCanUseGsonWithoutSerializableAnnotation() {
        val engine = RecordingEngine()
        val api = NetworkClient.Builder(engine).build().createGeneratedGsonPingApi()

        val result = runImmediate { api.echo(GsonPingRequest("hello")).await() }

        assertEquals("pong", assertIs<NetworkResult.Success<GsonPingResponse>>(result).data.value)
        assertEquals("{\"value\":\"hello\"}", engine.lastRequest?.body)
        assertEquals("application/json; charset=utf-8", engine.lastRequest?.headers?.get("Content-Type"))
    }

    @Test
    fun generatedApiSupportsFormMultipartDynamicUrlAndStreaming() {
        val engine = RecordingEngine()
        val api = NetworkClient.Builder(engine).build().createGeneratedAdvancedApi()

        runImmediate { api.submitForm("bird watcher", mapOf("page" to "1", "ignored" to null)).await() }
        assertEquals("name=bird%20watcher&page=1", engine.lastRequest?.body)
        assertEquals(
            "application/x-www-form-urlencoded; charset=utf-8",
            engine.lastRequest?.headers?.get("Content-Type"),
        )

        runImmediate { api.upload("photo".encodeToByteArray(), "sparrow").await() }
        assertTrue(engine.lastRequest?.bodyBytes?.decodeToString().orEmpty().contains("filename=\"bird.jpg\""))
        assertTrue(engine.lastRequest?.headers?.get("Content-Type").orEmpty().startsWith("multipart/form-data; boundary="))

        runImmediate { api.external("https://cdn.example.com/v1/file").await() }
        assertEquals("https://cdn.example.com/v1/file", engine.lastRequest?.relativePath)
        assertTrue(engine.lastRequest?.allowAbsoluteUrl == true)

        val bytes = runImmediate { api.download().await() }
        assertTrue(assertIs<NetworkResult.Success<ByteArray>>(bytes).data.isNotEmpty())
        assertTrue(engine.lastRequest?.streamResponse == true)
    }

    @Test
    fun cancellingOneCoalescedGetKeepsTheOtherTransportAlive() = runBlocking {
        val engine = GatedEngine()
        var candidates = 0
        val secondCandidate = CompletableDeferred<Unit>()
        val client = NetworkClient.Builder(engine)
            .coalesceRequests {
                candidates++
                if (candidates == 2) secondCandidate.complete(Unit)
                it.method == NetworkMethod.GET
            }
            .build()

        val first = async {
            client.executeRaw(
                NetworkRawRequest("/v1/ping", NetworkMethod.GET, headers = mapOf("X-Request-ID" to "first")),
            )
        }
        engine.started.await()
        val second = async {
            client.executeRaw(
                NetworkRawRequest("/v1/ping", NetworkMethod.GET, headers = mapOf("X-Request-ID" to "second")),
            )
        }
        secondCandidate.await()
        yield()
        first.cancelAndJoin()
        engine.release.complete(Unit)

        assertTrue(first.isCancelled)
        assertIs<NetworkRawResult.Response>(second.await())
        assertEquals(1, engine.callCount)
    }

    @Test
    fun concurrencyLimitFailsFastInsteadOfGrowingAnUnboundedQueue() = runBlocking {
        val engine = GatedEngine()
        val client = NetworkClient.Builder(engine).limitConcurrency(1, maxQueueWaitMillis = 0L).build()
        val first = async { client.executeRaw(NetworkRawRequest("/v1/first", NetworkMethod.GET)) }
        engine.started.await()

        val throttled = client.executeRaw(NetworkRawRequest("/v1/second", NetworkMethod.GET))

        assertEquals(
            NetworkFailureCategory.CLIENT_THROTTLED,
            assertIs<NetworkRawResult.Failure>(throttled).value.category,
        )
        engine.release.complete(Unit)
        assertIs<NetworkRawResult.Response>(first.await())
        assertEquals(1, engine.callCount)
    }

    @Test
    fun productionTransportRejectsCleartextBaseUrl() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            RetrofitNetworkEngine("http://127.0.0.1:8080", tlsPolicy = NetworkTlsPolicy())
        }
    }

    private class RecordingEngine : NetworkEngine {
        var lastRequest: NetworkRawRequest? = null

        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
            lastRequest = request
            return NetworkRawResponse(200, "{\"value\":\"pong\"}")
        }

        override fun cancelAll() = Unit
    }

    private class GatedEngine : NetworkEngine {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var callCount = 0

        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
            callCount++
            started.complete(Unit)
            release.await()
            return NetworkRawResponse(200, "{}")
        }

        override fun cancelAll() = Unit
    }

    private fun <T> runImmediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        })
        return requireNotNull(outcome).getOrThrow()
    }
}

@NetworkService(serialization = NetworkSerialization.GSON)
internal interface GeneratedGsonPingApi {
    @POST("/v1/ping")
    fun echo(@Body request: GsonPingRequest): NetworkCall<GsonPingResponse>
}

internal data class GsonPingRequest(val value: String)

internal data class GsonPingResponse(val value: String)

@NetworkService
internal interface GeneratedAdvancedApi {
    @FormUrlEncoded
    @POST("/v1/form")
    fun submitForm(
        @Field("name") name: String,
        @FieldMap extras: Map<String, String?>,
    ): NetworkCall<PingResponse>

    @Multipart
    @POST("/v1/upload")
    fun upload(
        @Part(value = "file", fileName = "bird.jpg", contentType = "image/jpeg") bytes: ByteArray,
        @Part("caption") caption: String,
    ): NetworkCall<PingResponse>

    @GET
    fun external(@Url url: String): NetworkCall<PingResponse>

    @Streaming
    @GET("/v1/file")
    fun download(): NetworkCall<ByteArray>
}
