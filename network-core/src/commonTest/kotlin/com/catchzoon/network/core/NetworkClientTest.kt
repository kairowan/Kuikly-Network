package com.catchzoon.network.core

import com.catchzoon.network.api.NetworkApi
import com.catchzoon.network.api.NetworkDecoder
import com.catchzoon.network.api.NetworkEndpoint
import com.catchzoon.network.api.NetworkEncoder
import com.catchzoon.network.api.networkUrl
import com.catchzoon.network.api.resolveAnnotatedNetworkUrl
import com.catchzoon.network.annotation.Body
import com.catchzoon.network.annotation.GET
import com.catchzoon.network.annotation.Headers
import com.catchzoon.network.annotation.IdempotencyKey
import com.catchzoon.network.annotation.NetworkService
import com.catchzoon.network.annotation.POST
import com.catchzoon.network.annotation.Path
import com.catchzoon.network.annotation.Query
import com.catchzoon.network.annotation.Retry
import com.catchzoon.network.annotation.Timeout
import com.catchzoon.network.cache.NetworkCacheEntry
import com.catchzoon.network.cache.NetworkCacheStore
import com.catchzoon.network.interceptor.RefreshingBearerAuthInterceptor
import com.catchzoon.network.interceptor.circuitBreaker
import com.catchzoon.network.interceptor.metrics
import com.catchzoon.network.interceptor.responseCache
import com.catchzoon.network.monitor.NetworkMetricsCollector
import com.catchzoon.network.monitor.NetworkQuality
import com.catchzoon.network.platform.resolveNetworkUrl
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NetworkClientTest {
    @Test
    fun endpointInterceptorAndStateFlowShareOneExecutionPath() {
        val engine = RecordingEngine()
        val client = NetworkClient.Builder(engine)
            .addInterceptor { chain ->
                chain.proceed(chain.request.copy(headers = chain.request.headers + ("X-Test" to "yes")))
            }
            .build()
        val states = runImmediate {
            client.getFlow("/v1/ping") { it.optString("value").uppercase() }.toList()
        }

        assertIs<NetworkState.Loading>(states[0])
        assertEquals("PONG", assertIs<NetworkState.Success<String>>(states[1]).data)
        assertEquals("yes", engine.lastRequest?.headers?.get("X-Test"))
    }

    @Test
    fun businessApiReturnsComposableNetworkCall() {
        val client = NetworkClient.Builder(RecordingEngine()).build()
        val result = runImmediate {
            PingApi(client).ping()
                .map(String::uppercase)
                .await()
        }

        assertEquals("PONG", assertIs<NetworkResult.Success<String>>(result).data)
    }

    @Test
    fun retryOnlyReplaysSafeRequestsByDefault() {
        val getEngine = FailOnceEngine()
        val getResult = runImmediate {
            NetworkClient.Builder(getEngine).build()
                .get("/v1/ping") { it.optString("value") }
                .retry(immediateRetryPolicy())
                .await()
        }
        assertIs<NetworkResult.Success<String>>(getResult)
        assertEquals(2, getEngine.callCount)

        val postEngine = FailOnceEngine()
        val postResult = runImmediate {
            NetworkClient.Builder(postEngine).build()
                .post("/v1/ping", decoder = { it.optString("value") })
                .retry(immediateRetryPolicy())
                .await()
        }
        assertIs<NetworkResult.Failure>(postResult)
        assertEquals(1, postEngine.callCount)

        val idempotentPostEngine = FailOnceEngine()
        val idempotentPostResult = runImmediate {
            NetworkClient.Builder(idempotentPostEngine).build()
                .post(
                    path = "/v1/ping",
                    headers = mapOf("Idempotency-Key" to "request-1"),
                    decoder = { it.optString("value") },
                )
                .retry(immediateRetryPolicy())
                .await()
        }
        assertIs<NetworkResult.Success<String>>(idempotentPostResult)
        assertEquals(2, idempotentPostEngine.callCount)
    }

    @Test
    fun typedApiSerializesDtoAndUnwrapsResponseOnce() {
        val engine = WrappedRecordingEngine()
        val client = NetworkClient.Builder(engine)
            .responseAdapter { response ->
                val envelope = Json.parseToJsonElement(response.body).jsonObject
                if (envelope["code"]?.jsonPrimitive?.intOrNull == 0) {
                    NetworkPayload.Data(envelope["data"]?.toString().orEmpty())
                } else {
                    NetworkPayload.Failure(NetworkFailure(code = "business_error"))
                }
            }
            .build()
        val result = runImmediate {
            TypedPingApi(client).echo(EchoRequest("ping")).await()
        }

        assertEquals("pong", assertIs<NetworkResult.Success<PingResponse>>(result).data.value)
        assertEquals("ping", Json.parseToJsonElement(engine.lastRequest?.body.orEmpty()).jsonObject.optString("value"))
        assertEquals("echo-ping", engine.lastRequest?.headers?.get("Idempotency-Key"))
        assertEquals(12, engine.lastRequest?.timeoutSeconds)
    }

    @Test
    fun bearerInterceptorRefreshesAndReplaysOnlyOnce() {
        var token = "expired"
        var refreshCount = 0
        val engine = AuthEngine()
        val client = NetworkClient.Builder(engine)
            .addInterceptor(
                RefreshingBearerAuthInterceptor(
                    currentToken = { token },
                    refreshToken = {
                        refreshCount++
                        "fresh".also { token = it }
                    },
                ),
            )
            .build()

        val result = runImmediate { TypedPingApi(client).ping().await() }

        assertEquals("pong", assertIs<NetworkResult.Success<PingResponse>>(result).data.value)
        assertEquals(1, refreshCount)
        assertEquals(2, engine.callCount)
    }

    @Test
    fun urlBuilderEncodesDynamicValuesInsteadOfConcatenatingThem() {
        val url = networkUrl("/v1/cards") {
            segment("a/b 中文")
            query("next cursor", "x&y")
        }

        assertEquals("/v1/cards/a%2Fb%20%E4%B8%AD%E6%96%87?next%20cursor=x%26y", url)
        assertEquals(
            "https://api.example.com/base/v1/cards/a%2Fb%20%E4%B8%AD%E6%96%87?next%20cursor=x%26y",
            resolveNetworkUrl("https://api.example.com/base", url),
        )
    }

    @Test
    fun annotatedUrlResolvesPathParametersOnEveryPlatform() {
        assertEquals(
            "/v1/cards/a%2Fb?source=android%20demo",
            resolveAnnotatedNetworkUrl(
                template = "/v1/cards/{id}",
                pathValues = mapOf("id" to "a/b"),
                queryValues = listOf("source" to "android demo"),
            ),
        )
    }

    @Test
    fun routeKeepsUrlAndOperatorsInsideApiDeclaration() {
        val engine = RecordingEngine()
        val result = runImmediate {
            TypedPingApi(NetworkClient.Builder(engine).defaultTimeout(17).build()).detail("a/b 中文").await()
        }

        assertIs<NetworkResult.Success<PingResponse>>(result)
        assertEquals("/v1/ping/a%2Fb%20%E4%B8%AD%E6%96%87?source=contract", engine.lastRequest?.relativePath)
        assertEquals(9, engine.lastRequest?.timeoutSeconds)
    }

    @Test
    fun clientDefaultsApplyWithoutRepeatingPolicyAtEachCall() {
        val engine = FailOnceEngine()
        val result = runImmediate {
            NetworkClient.Builder(engine)
                .defaultTimeout(17)
                .defaultResponseLimit(1_024L)
                .defaultRetry(immediateRetryPolicy())
                .build()
                .get("/v1/ping") { it.optString("value") }
                .await()
        }

        assertIs<NetworkResult.Success<String>>(result)
        assertEquals(2, engine.callCount)
        assertEquals(17, engine.lastRequest?.timeoutSeconds)
        assertEquals(1_024L, engine.lastRequest?.maxResponseBytes)
    }

    @Test
    fun responseCacheReturnsFreshAndStaleDataWithoutHidingItsSource() {
        val engine = SwitchableEngine()
        val clock = MutableNetworkClock()
        val client = NetworkClient.Builder(engine).responseCache(clock = clock).build()
        val call = client.get("/v1/ping") { it.optString("value") }.staleIfError(1, 60)

        val online = runImmediate { call.await() }
        val cached = runImmediate { call.await() }
        clock.nowMillis = 60_500L
        engine.fail = true
        val stale = runImmediate { call.await() }

        assertEquals(NetworkResponseSource.NETWORK, assertIs<NetworkResult.Success<String>>(online).source)
        assertEquals(NetworkResponseSource.MEMORY_CACHE, assertIs<NetworkResult.Success<String>>(cached).source)
        assertEquals(NetworkResponseSource.STALE_CACHE, assertIs<NetworkResult.Success<String>>(stale).source)
        assertEquals(2, engine.callCount)
    }

    @Test
    fun cacheFailureDoesNotBreakOnlineRequestOrExposeCredentialsInKey() {
        val engine = RecordingEngine()
        val client = NetworkClient.Builder(engine).responseCache(store = FailingCacheStore()).build()
        val request = NetworkRawRequest(
            relativePath = "/v1/ping",
            method = NetworkMethod.GET,
            headers = mapOf("Authorization" to "Bearer private-token", "X-Request-ID" to "first"),
            cachePolicy = NetworkCachePolicy.cacheFirst(60),
        )

        val result = runImmediate { client.executeRaw(request) }
        val firstKey = request.stableIdentityKey(includeTransportPolicy = false)
        val secondKey = request.copy(headers = request.headers + ("X-Request-ID" to "second"))
            .stableIdentityKey(includeTransportPolicy = false)
        val otherUserKey = request.copy(headers = request.headers + ("Authorization" to "Bearer another-token"))
            .stableIdentityKey(includeTransportPolicy = false)

        assertIs<NetworkRawResult.Response>(result)
        assertFalse(firstKey.contains("private-token"))
        assertEquals(firstKey, secondKey)
        assertNotEquals(firstKey, otherUserKey)
    }

    @Test
    fun circuitBreakerFailsFastAndRecoversThroughOneProbe() {
        val engine = SwitchableEngine().apply { fail = true }
        val clock = MutableNetworkClock()
        val client = NetworkClient.Builder(engine)
            .circuitBreaker(
                policy = com.catchzoon.network.resilience.NetworkCircuitBreakerPolicy(
                    failureThreshold = 2,
                    openDurationMillis = 1_000L,
                ),
                clock = clock,
            )
            .build()
        val call = client.get("/v1/ping") { it.optString("value") }

        runImmediate { call.await() }
        runImmediate { call.await() }
        val open = runImmediate { call.await() }
        assertEquals(NetworkFailureCategory.CIRCUIT_OPEN, assertIs<NetworkResult.Failure>(open).error.category)
        assertEquals(2, engine.callCount)

        clock.nowMillis = 1_000L
        engine.fail = false
        assertIs<NetworkResult.Success<String>>(runImmediate { call.await() })
        assertEquals(3, engine.callCount)
    }

    @Test
    fun metricsExposeCacheHitsAndOfflineQualityAsStateFlow() {
        val clock = MutableNetworkClock()
        val collector = NetworkMetricsCollector(maxRecentSamples = 10)
        val engine = SwitchableEngine()
        val client = NetworkClient.Builder(engine)
            .responseCache(clock = clock)
            .metrics(collector)
            .build()
        val cachedCall = client.get("/v1/ping") { it.optString("value") }.cacheFirst(60)

        runImmediate { cachedCall.await() }
        runImmediate { cachedCall.await() }
        assertEquals(2L, collector.state.value.successfulRequests)
        assertEquals(1L, collector.state.value.cacheHits)

        engine.fail = true
        repeat(3) { index ->
            runImmediate {
                client.get("/v1/failure/$index") { Unit }.await()
            }
        }
        assertEquals(NetworkQuality.OFFLINE, collector.state.value.quality)
        assertEquals(3L, collector.state.value.failedRequests)
    }

    @Test
    fun metricsCountHttpServerErrorAsFailure() {
        val collector = NetworkMetricsCollector(maxRecentSamples = 10)
        val client = NetworkClient.Builder(HttpFailureEngine()).metrics(collector).build()

        val result = runImmediate { client.get("/v1/ping") { Unit }.await() }

        assertIs<NetworkResult.Failure>(result)
        assertEquals(0L, collector.state.value.successfulRequests)
        assertEquals(1L, collector.state.value.failedRequests)
        assertEquals(1L, collector.state.value.failuresByCategory[NetworkFailureCategory.HTTP])
        assertEquals(NetworkQuality.DEGRADED, collector.state.value.quality)
    }

    @Test
    fun tlsPolicyRequiresTwoRotationSafePinsPerHost() {
        val first = certificateSha256Pin("a".repeat(64))
        val second = certificateSha256Pin("b".repeat(64))
        val policy = NetworkTlsPolicy(certificatePins = mapOf("api.catchzoon.app" to setOf(first, second)))

        assertEquals(setOf(first, second), policy.pinsForHost("API.CATCHZOON.APP"))
        assertFailsWith<IllegalArgumentException> {
            NetworkTlsPolicy(certificatePins = mapOf("api.catchzoon.app" to setOf(first)))
        }
    }

    @Test
    fun callOperatorsRemainComposableAndImmutable() {
        val primary = FailOnceEngine()
        val fallback = RecordingEngine()
        val primaryCall = NetworkClient.Builder(primary).build()
            .get("/v1/ping") { it.optString("value") }
            .retryWhen(immediateRetryPolicy()) { false }
            .header("x-trace", "old")
            .header("X-Trace", "new")
        val fallbackCall = NetworkClient.Builder(fallback).build()
            .get("/v1/ping") { it.optString("value") }

        val result = runImmediate {
            primaryCall
                .fallbackTo(fallbackCall)
                .mapSuspend(String::uppercase)
                .validate(
                    predicate = { it == "PONG" },
                    failure = { NetworkFailure(code = "unexpected_value") },
                )
                .asResultFlow()
                .toList()
                .single()
        }

        assertEquals("PONG", assertIs<NetworkResult.Success<String>>(result).data)
        assertEquals(1, primary.callCount)
        assertEquals("new", primary.lastRequest?.headers?.get("X-Trace"))
    }

    @Test
    fun failureObserverRunsOnlyAfterRetryIsExhausted() {
        val engine = FailOnceEngine()
        var observedFailures = 0

        val result = runImmediate {
            NetworkClient.Builder(engine).build()
                .get("/v1/ping") { it.optString("value") }
                .retry(immediateRetryPolicy())
                .onFailure { observedFailures++ }
                .await()
        }

        assertIs<NetworkResult.Success<String>>(result)
        assertEquals(2, engine.callCount)
        assertEquals(0, observedFailures)
    }

    @Test
    fun cancelledTransportIsNotRetriedAndEmitsCancelledEvent() {
        val events = mutableListOf<NetworkEvent>()
        val client = NetworkClient.Builder(CancelledEngine())
            .addEventListener(events::add)
            .build()

        val result = runImmediate { client.get("/v1/ping") { Unit }.retry(immediateRetryPolicy()).await() }

        assertEquals(NetworkFailureCategory.CANCELLED, assertIs<NetworkResult.Failure>(result).error.category)
        assertEquals(1, events.filterIsInstance<NetworkEvent.Cancelled>().size)
        assertEquals(0, events.filterIsInstance<NetworkEvent.Failed>().size)
    }

    private class RecordingEngine : NetworkEngine {
        var lastRequest: NetworkRawRequest? = null

        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
            lastRequest = request
            return NetworkRawResponse(200, "{\"value\":\"pong\"}")
        }

        override fun cancelAll() = Unit
    }

    private class FailOnceEngine : NetworkEngine {
        var callCount = 0
        var lastRequest: NetworkRawRequest? = null

        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
            lastRequest = request
            callCount++
            if (callCount == 1) {
                throw NetworkTransportException(
                    NetworkFailure(
                        code = "network_unavailable",
                        category = NetworkFailureCategory.CONNECTIVITY,
                        retryable = true,
                    ),
                )
            }
            return NetworkRawResponse(200, "{\"value\":\"pong\"}")
        }

        override fun cancelAll() = Unit
    }

    private class WrappedRecordingEngine : NetworkEngine {
        var lastRequest: NetworkRawRequest? = null

        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
            lastRequest = request
            return NetworkRawResponse(200, "{\"code\":0,\"data\":{\"value\":\"pong\"}}")
        }

        override fun cancelAll() = Unit
    }

    private class AuthEngine : NetworkEngine {
        var callCount = 0

        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
            callCount++
            return if (request.headers["Authorization"] == "Bearer fresh") {
                NetworkRawResponse(200, "{\"value\":\"pong\"}")
            } else {
                NetworkRawResponse(401, "{}")
            }
        }

        override fun cancelAll() = Unit
    }

    private class CancelledEngine : NetworkEngine {
        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse = throw Exception("Canceled")

        override fun cancelAll() = Unit
    }

    private class SwitchableEngine : NetworkEngine {
        var callCount = 0
        var fail = false

        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
            callCount++
            if (fail) throw NetworkTransportException(
                NetworkFailure(
                    code = "network_unavailable",
                    category = NetworkFailureCategory.CONNECTIVITY,
                    retryable = true,
                ),
            )
            return NetworkRawResponse(200, "{\"value\":\"pong\"}")
        }

        override fun cancelAll() = Unit
    }

    private class HttpFailureEngine : NetworkEngine {
        override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse = NetworkRawResponse(503, "{}")
        override fun cancelAll() = Unit
    }

    private class MutableNetworkClock(var nowMillis: Long = 0L) : NetworkClock {
        override fun nowMillis(): Long = nowMillis
    }

    private class FailingCacheStore : NetworkCacheStore {
        override suspend fun get(key: String): NetworkCacheEntry = error("read failed")
        override suspend fun put(key: String, entry: NetworkCacheEntry): Unit = error("write failed")
        override suspend fun clear(): Unit = error("clear failed")
    }

    private class PingApi(private val client: NetworkClient) {
        fun ping(): NetworkCall<String> = client.get("/v1/ping") { it.optString("value") }
    }

    private class TypedPingApi(client: NetworkClient) : NetworkApi(client) {
        val ping = get<PingResponse>("/v1/ping")

        val detail = getBy<String, PingResponse>("/v1/ping/{id}") {
            path("id") { it }
            query("source") { "contract" }
            timeout(9)
        }

        val echo = post<EchoRequest, PingResponse>("/v1/ping") {
            timeout(12)
            idempotencyKey { request -> "echo-${request.value}" }
        }
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

    private fun immediateRetryPolicy(): NetworkRetryPolicy = NetworkRetryPolicy(
        maxAttempts = 2,
        initialDelayMillis = 0L,
        maxDelayMillis = 0L,
        jitterRatio = 0.0,
    )
}

private fun JsonObject.optString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun <T> NetworkClient.get(path: String, decoder: (JsonObject) -> T): NetworkCall<T> = call(
    endpoint = NetworkEndpoint(
        method = NetworkMethod.GET,
        path = { path },
        requestEncoder = NetworkEncoder<Unit> { "" },
        responseDecoder = NetworkDecoder { decoder(Json.parseToJsonElement(it).jsonObject) },
    ),
    request = Unit,
)

private fun <T> NetworkClient.getFlow(path: String, decoder: (JsonObject) -> T) = get(path, decoder).asFlow()

private fun <T> NetworkClient.post(
    path: String,
    headers: Map<String, String> = emptyMap(),
    decoder: (JsonObject) -> T,
): NetworkCall<T> = call(
    endpoint = NetworkEndpoint(
        method = NetworkMethod.POST,
        path = { path },
        requestEncoder = NetworkEncoder<Unit> { "" },
        responseDecoder = NetworkDecoder { decoder(Json.parseToJsonElement(it).jsonObject) },
    ),
    request = Unit,
    options = NetworkRequestOptions(headers = headers),
)

@NetworkService
internal interface GeneratedPingApi {
    @Headers("Content-Type: application/json")
    @GET("/v1/ping/{id}")
    @Timeout(8)
    suspend fun detail(
        @Path("id") id: String,
        @Query("source") source: String?,
    ): NetworkResult<PingResponse>

    @POST("/v1/ping")
    @Retry(maxAttempts = 2)
    fun echo(
        @Body request: EchoRequest,
        @IdempotencyKey requestId: String,
    ): NetworkCall<PingResponse>

    @GET("/v1/ping")
    suspend fun direct(): PingResponse

    @GET("/v1/ping")
    fun observe(): kotlinx.coroutines.flow.Flow<NetworkState<PingResponse>>

    @GET("/v1/ping/{id}")
    @com.catchzoon.network.annotation.Cache(maxAgeSeconds = 60, staleIfErrorSeconds = 300)
    fun search(
        @Path("id") id: String,
        @com.catchzoon.network.annotation.QueryMap() queries: Map<String, String?>,
        @com.catchzoon.network.annotation.HeaderMap() headers: Map<String, String?>,
        @com.catchzoon.network.annotation.RequestId() requestId: String,
    ): NetworkCall<PingResponse>
}

@Serializable
internal data class EchoRequest(val value: String)

@Serializable
internal data class PingResponse(val value: String)
