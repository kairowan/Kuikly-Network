package com.catchzoon.network.platform

import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkTlsPolicy
import com.catchzoon.network.core.isSafeAbsoluteNetworkUrl
import com.catchzoon.network.core.isSafeRelativeNetworkPath

/** 鸿蒙网络需要 Kuikly `Pager`，调用方应改用 `createKuiklyNetworkClient`。 */
public actual fun createPlatformNetworkEngine(
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy,
): NetworkEngine = error("鸿蒙端请使用 createKuiklyNetworkClient(pager, baseUrl)")

/** 鸿蒙侧仅拼接已经通过公共安全校验的地址。 */
public actual fun resolveNetworkUrl(baseUrl: String, relativePath: String): String? = when {
    isSafeAbsoluteNetworkUrl(relativePath) -> relativePath
    !isSafeRelativeNetworkPath(relativePath) -> null
    else -> baseUrl.trimEnd('/') + relativePath
}
