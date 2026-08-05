package com.catchzoon.network.facade

import com.catchzoon.network.core.NetworkCall
import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkMethod
import com.catchzoon.network.core.NetworkRequestOptions
import kotlinx.serialization.serializer

/** 创建自动反序列化的 GET 调用。 */
public inline fun <reified Response> NetworkClient.get(
    path: String,
    options: NetworkRequestOptions = NetworkRequestOptions(),
): NetworkCall<Response> = typedCall(
    method = NetworkMethod.GET,
    path = path,
    request = Unit,
    requestSerializer = null,
    responseDeserializer = serializer<Response>(),
    options = options,
)

/** 创建带类型化请求体和响应体的 POST 调用。 */
public inline fun <reified Request, reified Response> NetworkClient.post(
    path: String,
    body: Request,
    options: NetworkRequestOptions = NetworkRequestOptions(),
): NetworkCall<Response> = typedCall(
    method = NetworkMethod.POST,
    path = path,
    request = body,
    requestSerializer = serializer<Request>(),
    responseDeserializer = serializer<Response>(),
    options = options,
)

/** 创建无请求体、类型化响应的 POST 调用。 */
public inline fun <reified Response> NetworkClient.post(
    path: String,
    options: NetworkRequestOptions = NetworkRequestOptions(),
): NetworkCall<Response> = typedCall(
    method = NetworkMethod.POST,
    path = path,
    request = Unit,
    requestSerializer = null,
    responseDeserializer = serializer<Response>(),
    options = options,
)

/** 创建带类型化请求体和响应体的 PUT 调用。 */
public inline fun <reified Request, reified Response> NetworkClient.put(
    path: String,
    body: Request,
    options: NetworkRequestOptions = NetworkRequestOptions(),
): NetworkCall<Response> = typedCall(
    NetworkMethod.PUT,
    path,
    body,
    serializer<Request>(),
    serializer<Response>(),
    options,
)

/** 创建带类型化请求体和响应体的 PATCH 调用。 */
public inline fun <reified Request, reified Response> NetworkClient.patch(
    path: String,
    body: Request,
    options: NetworkRequestOptions = NetworkRequestOptions(),
): NetworkCall<Response> = typedCall(
    NetworkMethod.PATCH,
    path,
    body,
    serializer<Request>(),
    serializer<Response>(),
    options,
)

/** 创建类型化 DELETE 调用。 */
public inline fun <reified Response> NetworkClient.delete(
    path: String,
    options: NetworkRequestOptions = NetworkRequestOptions(),
): NetworkCall<Response> = typedCall(
    NetworkMethod.DELETE,
    path,
    Unit,
    null,
    serializer<Response>(),
    options,
)
