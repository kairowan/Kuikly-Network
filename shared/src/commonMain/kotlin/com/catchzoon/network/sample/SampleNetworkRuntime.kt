package com.catchzoon.network.sample

import com.catchzoon.network.core.NetworkClients
import com.catchzoon.network.platform.createNetworkClient
import com.catchzoon.network.sample.api.SAMPLE_CLIENT

/** 宿主在 Application/AppDelegate 初始化一次，页面和 Repository 不再负责创建 Client。 */
public object SampleNetworkRuntime {
    public fun initialize(defaultBaseUrl: String, sampleBaseUrl: String) {
        NetworkClients.initialize(
            defaultClient = createNetworkClient(defaultBaseUrl),
            namedClients = mapOf(SAMPLE_CLIENT to createNetworkClient(sampleBaseUrl)),
        )
    }
}
