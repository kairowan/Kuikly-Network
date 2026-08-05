package com.catchzoon.network.sample

import com.catchzoon.network.core.NetworkState
import com.catchzoon.network.core.NetworkClients
import com.catchzoon.network.kuikly.KuiklyNetworkModule
import com.catchzoon.network.kuikly.closeNetworkScope
import com.catchzoon.network.kuikly.createKuiklyNetworkClient
import com.catchzoon.network.kuikly.networkScope
import com.catchzoon.network.sample.api.SampleApi
import com.catchzoon.network.sample.api.SAMPLE_CLIENT
import com.catchzoon.network.sample.api.provideSampleApi
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 展示注解 API、状态回调和页面生命周期取消能力的最小 Kuikly 页面。 */
@Page("network_sample", supportInLocal = true)
internal class NetworkSamplePage : Pager() {
    private var statusText by observable("点击按钮发起请求")
    private lateinit var api: SampleApi

    override fun createExternalModules(): Map<String, Module> = mapOf(
        KuiklyNetworkModule().moduleName() to KuiklyNetworkModule(),
    )

    override fun created() {
        super.created()
        val baseUrl = pageData.params.optString("baseUrl", DEFAULT_BASE_URL).trimEnd('/')
        val client = if (NetworkClients.isInitialized) {
            NetworkClients.client(SAMPLE_CLIENT)
        } else {
            // HarmonyOS 的内置传输仍需要 Pager；Android/iOS 会在宿主入口完成全局初始化。
            createKuiklyNetworkClient(this, baseUrl)
        }
        api = provideSampleApi(client)
    }

    override fun pageWillDestroy() {
        closeNetworkScope()
        super.pageWillDestroy()
    }

    override fun body(): ViewBuilder {
        val page = this
        return {
            View {
                attr {
                    flex(1f)
                    padding(24f)
                    backgroundColor(Color(0xFFF5F7FA))
                }
                Text {
                    attr {
                        marginTop(72f)
                        text("Kuikly Network")
                        fontSize(30f)
                        fontWeightSemiBold()
                        color(Color(0xFF1F2329))
                    }
                }
                Text {
                    attr {
                        marginTop(12f)
                        text("KMP · KSP · Flow · Lifecycle")
                        fontSize(14f)
                        color(Color(0xFF646A73))
                    }
                }
                View {
                    attr {
                        marginTop(36f)
                        minHeight(108f)
                        padding(18f)
                        borderRadius(16f)
                        backgroundColor(Color(0xFFFFFFFF))
                    }
                    Text {
                        attr {
                            text(page.statusText)
                            fontSize(15f)
                            lineHeight(22f)
                            color(Color(0xFF333333))
                        }
                    }
                }
                View {
                    attr {
                        marginTop(24f)
                        height(52f)
                        borderRadius(14f)
                        backgroundColor(Color(0xFF3278FD))
                        allCenter()
                    }
                    event { click { page.loadStatus() } }
                    Text {
                        attr {
                            text("发送示例请求")
                            fontSize(16f)
                            fontWeightSemiBold()
                            color(Color(0xFFFFFFFF))
                        }
                    }
                }
                Text {
                    attr {
                        marginTop(18f)
                        text("离开页面时，在途请求会自动取消。")
                        fontSize(12f)
                        color(Color(0xFF8F959E))
                    }
                }
            }
        }
    }

    private fun loadStatus() {
        networkScope.launch(api.getStatus()) { state ->
            statusText = when (state) {
                NetworkState.Loading -> "请求中…"
                is NetworkState.Success -> "请求成功\nURL：${state.data.url}\n来源：${state.data.origin}"
                is NetworkState.Error -> "请求失败：${state.failure.message}"
            }
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://httpbun.com"
    }
}
