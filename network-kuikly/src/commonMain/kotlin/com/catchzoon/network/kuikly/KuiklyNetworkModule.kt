package com.catchzoon.network.kuikly

import com.catchzoon.network.core.NetworkMethod
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.module.NetworkResponse
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Kuikly 原生网络桥接，只传递原始字符串，公共层统一完成解析。 */
public class KuiklyNetworkModule : Module() {
    override fun moduleName(): String = NetworkModule.MODULE_NAME

    /** 保留原有调用签名；新代码由 Engine 传入自己的响应上限。 */
    public fun request(
        url: String,
        method: NetworkMethod,
        body: String,
        headers: Map<String, String>,
        timeoutSeconds: Int,
        callback: (String, Boolean, String, NetworkResponse) -> Unit,
    ): Unit = request(
        url = url,
        method = method,
        body = body,
        headers = headers,
        timeoutSeconds = timeoutSeconds,
        maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
        callback = callback,
    )

    /** 发起带响应体上限的原生桥接请求。 */
    public fun request(
        url: String,
        method: NetworkMethod,
        body: String,
        headers: Map<String, String>,
        timeoutSeconds: Int,
        maxResponseBytes: Long,
        callback: (String, Boolean, String, NetworkResponse) -> Unit,
    ) {
        val params = JSONObject()
            .put("url", url)
            .put("method", method.name)
            .put("param", body.toJsonObject())
            .put("headers", headers.toJsonObject())
            .put("timeout", timeoutSeconds)
            .put("maxResponseBytes", maxResponseBytes)
        toNative(methodName = METHOD_HTTP_REQUEST, param = params.toString(), callback = { result ->
            val raw = result ?: JSONObject()
            callback(
                raw.optString("data"),
                raw.optInt("success") != 0,
                raw.optString("errorMsg"),
                NetworkResponse(
                    runCatching { JSONObject(raw.optString("headers", "{}")) }.getOrElse { JSONObject() },
                    raw.optInt("statusCode").takeIf { raw.has("statusCode") },
                ),
            )
        })
    }

    /** 取消该页面通过鸿蒙 Bridge 发起的全部原生请求。 */
    public fun cancelAll() {
        toNative(methodName = METHOD_CANCEL_ALL, param = "{}")
    }

    private fun String.toJsonObject(): JSONObject =
        if (isBlank()) JSONObject() else runCatching { JSONObject(this) }.getOrElse { JSONObject() }

    private fun Map<String, String>.toJsonObject(): JSONObject =
        JSONObject().also { target -> forEach { (key, value) -> target.put(key, value) } }

    private companion object {
        const val METHOD_HTTP_REQUEST = "httpRequest"
        const val METHOD_CANCEL_ALL = "cancelAll"
        const val DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    }
}
