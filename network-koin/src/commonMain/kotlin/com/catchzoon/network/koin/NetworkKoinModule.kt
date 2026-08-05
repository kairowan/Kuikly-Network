package com.catchzoon.network.koin

import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkEngine
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 创建可被业务二次组合的 Koin 网络模块。
 *
 * Engine 使用工厂延迟创建，测试环境可直接覆盖同一类型绑定。
 */
public fun networkKoinModule(
    engine: () -> NetworkEngine,
    configure: NetworkClient.Builder.() -> Unit = {},
): Module = module {
    single<NetworkEngine> { engine() }
    single<NetworkClient> { NetworkClient.Builder(get()).apply(configure).build() }
}
