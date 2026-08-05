package com.catchzoon.network.sample.api

import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkClients

/** iOS 模拟器使用当前目标 KSP 生成的接口实现。 */
internal actual fun provideSampleApi(client: NetworkClient): SampleApi =
    if (NetworkClients.isInitialized) createSampleApi() else client.createSampleApi()
