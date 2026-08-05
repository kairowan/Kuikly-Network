package com.catchzoon.network.facade

import com.catchzoon.network.api.NetworkEndpoint
import com.catchzoon.network.codec.NetworkJson
import com.catchzoon.network.core.NetworkCall
import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkMethod
import com.catchzoon.network.core.NetworkRequestOptions
import com.catchzoon.network.core.NetworkRetryPolicy
import com.catchzoon.network.core.NetworkState
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.flow.Flow

/**
 * 面向普通业务调用的 JSON 门面。
 *
 * 所有请求都返回 NetworkCall，业务可以统一选择 await、asFlow、retry、map 或 recover。
 */
public class JsonNetworkClient internal constructor(private val client: NetworkClient) {
    /** 创建 GET 调用并返回原始 JSON 对象。 */
    public fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): NetworkCall<JSONObject> = get(path, headers) { it }

    /** 创建 GET 调用并直接解析成业务类型。 */
    public fun <Response> get(
        path: String,
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = request(
        method = NetworkMethod.GET,
        path = path,
        options = NetworkRequestOptions(headers = headers),
        decoder = decoder,
    )

    /** 创建 POST 调用并返回原始 JSON 对象。 */
    public fun post(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
    ): NetworkCall<JSONObject> = post(path, body, headers) { it }

    /** 创建 POST 调用并直接解析成业务类型。 */
    public fun <Response> post(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = request(
        method = NetworkMethod.POST,
        path = path,
        body = body,
        options = NetworkRequestOptions(headers = headers),
        decoder = decoder,
    )

    /** 创建 PUT 调用。 */
    public fun <Response> put(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = request(
        NetworkMethod.PUT,
        path,
        body,
        NetworkRequestOptions(headers = headers),
        decoder,
    )

    /** 创建 PATCH 调用。 */
    public fun <Response> patch(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = request(
        NetworkMethod.PATCH,
        path,
        body,
        NetworkRequestOptions(headers = headers),
        decoder,
    )

    /** 创建 DELETE 调用。 */
    public fun <Response> delete(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = request(
        NetworkMethod.DELETE,
        path,
        body,
        NetworkRequestOptions(headers = headers),
        decoder,
    )

    /** 创建任意 JSON 调用，供需要自定义策略的二次封装使用。 */
    public fun <Response> request(
        method: NetworkMethod,
        path: String,
        body: JSONObject = JSONObject(),
        options: NetworkRequestOptions = NetworkRequestOptions(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = client.call(endpoint(method, path, decoder), body, options)

    /** 兼容旧 GET Flow 入口；新业务直接使用 get(...).asFlow()。 */
    public fun <Response> getFlow(
        path: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        retryCount: Int = 0,
        retryDelayMillis: Long = 0L,
        decoder: (JSONObject) -> Response,
    ): Flow<NetworkState<Response>> = request(
        method = NetworkMethod.GET,
        path = path,
        options = legacyOptions(headers, timeoutSeconds, retryCount, retryDelayMillis),
        decoder = decoder,
    ).asFlow()

    /** 兼容旧 POST Flow 入口；无幂等键时不会自动重试写请求。 */
    public fun <Response> postFlow(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        retryCount: Int = 0,
        retryDelayMillis: Long = 0L,
        decoder: (JSONObject) -> Response,
    ): Flow<NetworkState<Response>> = request(
        method = NetworkMethod.POST,
        path = path,
        body = body,
        options = legacyOptions(headers, timeoutSeconds, retryCount, retryDelayMillis),
        decoder = decoder,
    ).asFlow()

    private fun <Response> endpoint(
        method: NetworkMethod,
        path: String,
        decoder: (JSONObject) -> Response,
    ) = NetworkEndpoint(
        method = method,
        path = { _: JSONObject -> path },
        requestEncoder = NetworkJson.encoder { it },
        responseDecoder = NetworkJson.decoder(decoder),
    )

    private fun legacyOptions(
        headers: Map<String, String>,
        timeoutSeconds: Int,
        retryCount: Int,
        retryDelayMillis: Long,
    ): NetworkRequestOptions = NetworkRequestOptions(
        headers = headers,
        timeoutSeconds = timeoutSeconds,
        retryPolicy = NetworkRetryPolicy(
            maxAttempts = retryCount.coerceIn(0, MAX_RETRY_COUNT) + 1,
            initialDelayMillis = retryDelayMillis.coerceAtLeast(0L),
            maxDelayMillis = retryDelayMillis.coerceAtLeast(0L),
            jitterRatio = 0.0,
        ),
    )

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val MAX_RETRY_COUNT = 5
    }
}

/** 获取适合常规 JSON 接口的简单调用门面。 */
public fun NetworkClient.json(): JsonNetworkClient = JsonNetworkClient(this)
