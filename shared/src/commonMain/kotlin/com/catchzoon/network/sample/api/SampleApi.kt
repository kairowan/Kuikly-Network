package com.catchzoon.network.sample.api

import com.catchzoon.network.annotation.GET
import com.catchzoon.network.annotation.NetworkService
import com.catchzoon.network.annotation.Query
import com.catchzoon.network.core.NetworkCall
import com.catchzoon.network.core.NetworkClient
import kotlinx.serialization.Serializable

/** 示例接口返回字段。 */
@Serializable
internal data class SampleResponse(
    val url: String = "",
    val origin: String = "",
)

/**
 * Retrofit 风格的示例接口。
 *
 * 调用方只声明请求方式、路径、参数和返回类型，具体实现由 network-ksp 生成。
 */
@NetworkService(client = SAMPLE_CLIENT)
internal interface SampleApi {
    /** 请求一个无副作用的 GET 接口。 */
    @GET("/get")
    fun getStatus(@Query("source") source: String = "kuikly"): NetworkCall<SampleResponse>
}

/** 隔离各目标平台的 KSP 生成工厂。 */
internal expect fun provideSampleApi(client: NetworkClient): SampleApi

internal const val SAMPLE_CLIENT = "sample"
