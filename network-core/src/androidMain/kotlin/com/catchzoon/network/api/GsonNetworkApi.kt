package com.catchzoon.network.api

import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkMethod
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Android 专用 Gson 声明式 API。
 *
 * Kuikly iOS 不支持 Gson，因此共享 Service 使用 [NetworkApi]，仅 Android 独占接口才使用本类。
 */
public abstract class GsonNetworkApi(
    @PublishedApi internal val gsonRouteClient: NetworkClient,
    @PublishedApi internal val gson: Gson = Gson(),
) {
    /** 声明无参数 GET。 */
    protected inline fun <reified Response> get(
        path: String,
        noinline configure: NetworkRouteBuilder<Unit>.() -> Unit = {},
    ): EmptyNetworkRoute<Response> = EmptyNetworkRoute(
        createGsonRoute(NetworkMethod.GET, path, null, gsonType<Response>(), configure),
    )

    /** 声明带动态参数的 GET。 */
    protected inline fun <reified Request, reified Response> getBy(
        path: String,
        noinline configure: NetworkRouteBuilder<Request>.() -> Unit,
    ): NetworkRoute<Request, Response> = createGsonRoute(
        NetworkMethod.GET,
        path,
        null,
        gsonType<Response>(),
        configure,
    )

    /** 声明带请求体 POST。 */
    protected inline fun <reified Request, reified Response> post(
        path: String,
        noinline configure: NetworkRouteBuilder<Request>.() -> Unit = {},
    ): NetworkRoute<Request, Response> = createGsonRoute(
        NetworkMethod.POST,
        path,
        gsonType<Request>(),
        gsonType<Response>(),
        configure,
    )

    /** 声明无请求体 POST。 */
    protected inline fun <reified Response> postEmpty(
        path: String,
        noinline configure: NetworkRouteBuilder<Unit>.() -> Unit = {},
    ): EmptyNetworkRoute<Response> = EmptyNetworkRoute(
        createGsonRoute(NetworkMethod.POST, path, null, gsonType<Response>(), configure),
    )

    /** 声明 PUT。 */
    protected inline fun <reified Request, reified Response> put(
        path: String,
        noinline configure: NetworkRouteBuilder<Request>.() -> Unit = {},
    ): NetworkRoute<Request, Response> = createGsonRoute(
        NetworkMethod.PUT,
        path,
        gsonType<Request>(),
        gsonType<Response>(),
        configure,
    )

    /** 声明 PATCH。 */
    protected inline fun <reified Request, reified Response> patch(
        path: String,
        noinline configure: NetworkRouteBuilder<Request>.() -> Unit = {},
    ): NetworkRoute<Request, Response> = createGsonRoute(
        NetworkMethod.PATCH,
        path,
        gsonType<Request>(),
        gsonType<Response>(),
        configure,
    )

    /** 声明无请求体 DELETE。 */
    protected inline fun <reified Response> delete(
        path: String,
        noinline configure: NetworkRouteBuilder<Unit>.() -> Unit = {},
    ): EmptyNetworkRoute<Response> = EmptyNetworkRoute(
        createGsonRoute(NetworkMethod.DELETE, path, null, gsonType<Response>(), configure),
    )

    @PublishedApi
    internal fun <Request, Response> createGsonRoute(
        method: NetworkMethod,
        path: String,
        requestType: Type?,
        responseType: Type,
        configure: NetworkRouteBuilder<Request>.() -> Unit,
    ): NetworkRoute<Request, Response> {
        val configuration = NetworkRouteBuilder<Request>().apply(configure).build(path)
        return NetworkRoute(
            pathTemplate = path,
            configuration = configuration,
            callFactory = { pathProvider, request, options ->
                gsonRouteClient.call(
                    endpoint = NetworkEndpoint(
                        method = method,
                        path = pathProvider,
                        requestEncoder = NetworkEncoder { value ->
                            if (requestType == null) "" else gson.toJson(value, requestType)
                        },
                        responseDecoder = NetworkDecoder { body -> gson.fromJson(body, responseType) },
                    ),
                    request = request,
                    options = options,
                )
            },
        )
    }
}

@PublishedApi
internal inline fun <reified T> gsonType(): Type = object : TypeToken<T>() {}.type
