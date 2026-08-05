package com.catchzoon.network.sample.android

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate

/** 只负责承载 Kuikly 页面；网络客户端已经由 Application 初始化。 */
internal class MainActivity : AppCompatActivity() {
    private val delegator = KuiklyRenderViewBaseDelegator(
        object : KuiklyRenderViewBaseDelegatorDelegate {
            override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) = Unit
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        setContentView(root)
        delegator.onAttach(
            root,
            "",
            PAGE_NAME,
            emptyMap(),
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (delegator.onBackPressed()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        delegator.onResume()
    }

    override fun onPause() {
        delegator.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        delegator.onDetach()
        super.onDestroy()
    }

    private companion object {
        const val PAGE_NAME = "network_sample"
    }
}
