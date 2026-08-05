package com.catchzoon.network.kuikly

import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkTlsPolicy
import com.catchzoon.network.platform.createPlatformNetworkEngine
import com.tencent.kuikly.core.pager.Pager

/** Android Kuikly 页面继续使用 Retrofit/OkHttp 引擎。 */
internal actual fun createKuiklyScopedNetworkEngine(
    pager: Pager,
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy,
): NetworkEngine = createPlatformNetworkEngine(baseUrl, tlsPolicy)
