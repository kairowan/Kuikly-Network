package com.catchzoon.network.api

import com.catchzoon.network.core.NetworkCall
import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.facade.JsonNetworkClient
import com.catchzoon.network.facade.json
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 旧 JSONObject 接口迁移兼容基类。
 *
 * 新业务使用 [NetworkApi]；只有尚未迁移为可序列化 DTO 的接口才继承此类。
 */
public abstract class LegacyJsonNetworkApi(protected val client: NetworkClient) {
    private val json: JsonNetworkClient = client.json()

    /** 兼容手动 JSON GET。 */
    protected fun <Response> get(
        path: String,
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = json.get(path, headers, decoder)

    /** 兼容手动 JSON POST。 */
    protected fun <Response> post(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = json.post(path, body, headers, decoder)

    /** 兼容手动 JSON PUT。 */
    protected fun <Response> put(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = json.put(path, body, headers, decoder)

    /** 兼容手动 JSON PATCH。 */
    protected fun <Response> patch(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = json.patch(path, body, headers, decoder)

    /** 兼容手动 JSON DELETE。 */
    protected fun <Response> delete(
        path: String,
        body: JSONObject = JSONObject(),
        headers: Map<String, String> = emptyMap(),
        decoder: (JSONObject) -> Response,
    ): NetworkCall<Response> = json.delete(path, body, headers, decoder)
}
