package com.catchzoon.network.kuikly

import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkTlsPolicy
import com.tencent.kuikly.core.pager.Pager

/** 鸿蒙通过页面持有的 `KRNetworkModule` 使用系统 NetworkKit。 */
internal actual fun createKuiklyScopedNetworkEngine(
    pager: Pager,
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy,
): NetworkEngine {
    require(tlsPolicy.certificatePins.isEmpty()) { "鸿蒙 Kuikly Bridge 暂不支持证书 Pin" }
    return KuiklyBridgeNetworkEngine(pager, baseUrl)
}
