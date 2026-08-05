package com.catchzoon.network.resilience

import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkInterceptor
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkTransportException
import kotlinx.coroutines.flow.StateFlow

/** 平台网络状态快照，具体监听由 Android、iOS 或鸿蒙宿主提供。 */
public data class NetworkConnectivity(
    val available: Boolean,
    val metered: Boolean = false,
    val transport: NetworkTransport = NetworkTransport.UNKNOWN,
)

public enum class NetworkTransport { WIFI, CELLULAR, ETHERNET, VPN, UNKNOWN }

public fun interface NetworkConnectivityProvider {
    public fun current(): NetworkConnectivity
}

/** 在系统已确认离线时快速失败，避免等待平台连接超时。 */
public class NetworkConnectivityInterceptor(
    private val provider: NetworkConnectivityProvider,
) : NetworkInterceptor {
    override suspend fun intercept(chain: NetworkInterceptor.Chain): NetworkRawResponse {
        if (!provider.current().available) throw unavailable()
        return chain.proceed()
    }

    private fun unavailable(): NetworkTransportException = NetworkTransportException(
        NetworkFailure(
            code = "network_offline",
            category = NetworkFailureCategory.CONNECTIVITY,
            message = "当前网络不可用",
            retryable = true,
        ),
    )
}

/** 可直接由 UI 收集的网络状态提供器。 */
public interface ObservableNetworkConnectivityProvider : NetworkConnectivityProvider {
    public val state: StateFlow<NetworkConnectivity>
    override fun current(): NetworkConnectivity = state.value
}
