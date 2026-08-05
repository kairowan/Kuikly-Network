package com.catchzoon.network.facade

import com.catchzoon.network.core.NetworkCall
import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkState
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.flow.Flow

/** 直接创建 GET 调用，不需要手动创建 JsonNetworkClient。 */
public fun NetworkClient.get(
    path: String,
    headers: Map<String, String> = emptyMap(),
): NetworkCall<JSONObject> = json().get(path, headers)

/** 直接创建 GET 调用并解析业务类型。 */
public fun <Response> NetworkClient.get(
    path: String,
    headers: Map<String, String> = emptyMap(),
    decoder: (JSONObject) -> Response,
): NetworkCall<Response> = json().get(path, headers, decoder)

/** 直接创建 POST 调用并返回原始 JSON。 */
public fun NetworkClient.post(
    path: String,
    body: JSONObject = JSONObject(),
    headers: Map<String, String> = emptyMap(),
): NetworkCall<JSONObject> = json().post(path, body, headers)

/** 直接创建 POST 调用并解析业务类型。 */
public fun <Response> NetworkClient.post(
    path: String,
    body: JSONObject = JSONObject(),
    headers: Map<String, String> = emptyMap(),
    decoder: (JSONObject) -> Response,
): NetworkCall<Response> = json().post(path, body, headers, decoder)

/** 兼容旧 GET Flow 入口。 */
public fun <Response> NetworkClient.getFlow(
    path: String,
    headers: Map<String, String> = emptyMap(),
    retryCount: Int = 0,
    decoder: (JSONObject) -> Response,
): Flow<NetworkState<Response>> = json().getFlow(
    path = path,
    headers = headers,
    retryCount = retryCount,
    decoder = decoder,
)

/** 兼容旧 POST Flow 入口。 */
public fun <Response> NetworkClient.postFlow(
    path: String,
    body: JSONObject = JSONObject(),
    headers: Map<String, String> = emptyMap(),
    retryCount: Int = 0,
    decoder: (JSONObject) -> Response,
): Flow<NetworkState<Response>> = json().postFlow(
    path = path,
    body = body,
    headers = headers,
    retryCount = retryCount,
    decoder = decoder,
)
