package com.catchzoon.network.api

import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkMethod
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer

/**
 * Retrofit 风格的类型化 API 声明基类。
 *
 * 子类只声明 Route 属性；URL、方法、动态参数和策略集中放在该 API 类中。
 */
public abstract class NetworkApi(
    @PublishedApi internal val routeClient: NetworkClient,
) {
    /** 声明无参数 GET。 */
    protected inline fun <reified Response> get(
        path: String,
        noinline configure: NetworkRouteBuilder<Unit>.() -> Unit = {},
    ): EmptyNetworkRoute<Response> = EmptyNetworkRoute(
        createRoute(
            NetworkMethod.GET,
            path,
            null,
            serializer<Response>(),
            configure,
        ),
    )

    /** 声明带动态路径或查询参数的 GET。 */
    protected inline fun <reified Request, reified Response> getBy(
        path: String,
        noinline configure: NetworkRouteBuilder<Request>.() -> Unit,
    ): NetworkRoute<Request, Response> = createRoute(
        NetworkMethod.GET,
        path,
        null,
        serializer<Response>(),
        configure,
    )

    /** 声明带类型化请求体的 POST。 */
    protected inline fun <reified Request, reified Response> post(
        path: String,
        noinline configure: NetworkRouteBuilder<Request>.() -> Unit = {},
    ): NetworkRoute<Request, Response> = createRoute(
        NetworkMethod.POST,
        path,
        serializer<Request>(),
        serializer<Response>(),
        configure,
    )

    /** 声明无请求体 POST。 */
    protected inline fun <reified Response> postEmpty(
        path: String,
        noinline configure: NetworkRouteBuilder<Unit>.() -> Unit = {},
    ): EmptyNetworkRoute<Response> = EmptyNetworkRoute(
        createRoute(
            NetworkMethod.POST,
            path,
            null,
            serializer<Response>(),
            configure,
        ),
    )

    /** 声明 PUT。 */
    protected inline fun <reified Request, reified Response> put(
        path: String,
        noinline configure: NetworkRouteBuilder<Request>.() -> Unit = {},
    ): NetworkRoute<Request, Response> = createRoute(
        NetworkMethod.PUT,
        path,
        serializer<Request>(),
        serializer<Response>(),
        configure,
    )

    /** 声明 PATCH。 */
    protected inline fun <reified Request, reified Response> patch(
        path: String,
        noinline configure: NetworkRouteBuilder<Request>.() -> Unit = {},
    ): NetworkRoute<Request, Response> = createRoute(
        NetworkMethod.PATCH,
        path,
        serializer<Request>(),
        serializer<Response>(),
        configure,
    )

    /** 声明无请求体 DELETE。 */
    protected inline fun <reified Response> delete(
        path: String,
        noinline configure: NetworkRouteBuilder<Unit>.() -> Unit = {},
    ): EmptyNetworkRoute<Response> = EmptyNetworkRoute(
        createRoute(
            NetworkMethod.DELETE,
            path,
            null,
            serializer<Response>(),
            configure,
        ),
    )

    @PublishedApi
    internal fun <Request, Response> createRoute(
        method: NetworkMethod,
        path: String,
        requestSerializer: SerializationStrategy<Request>?,
        responseDeserializer: DeserializationStrategy<Response>,
        configure: NetworkRouteBuilder<Request>.() -> Unit,
    ): NetworkRoute<Request, Response> {
        val builder = NetworkRouteBuilder<Request>().apply(configure)
        return NetworkRoute(
            pathTemplate = path,
            configuration = builder.build(path),
            callFactory = { pathProvider, request, options ->
                routeClient.typedCall(
                    method = method,
                    path = pathProvider,
                    request = request,
                    requestSerializer = requestSerializer,
                    responseDeserializer = responseDeserializer,
                    options = options,
                )
            },
        )
    }
}
