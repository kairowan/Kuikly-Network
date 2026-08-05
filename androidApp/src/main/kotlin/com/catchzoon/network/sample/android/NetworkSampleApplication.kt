package com.catchzoon.network.sample.android

import android.app.Application
import com.catchzoon.network.sample.SampleNetworkRuntime

/** 在进程启动时一次性创建默认与命名网络客户端。 */
class NetworkSampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SampleNetworkRuntime.initialize(
            defaultBaseUrl = BuildConfig.DEFAULT_BASE_URL,
            sampleBaseUrl = BuildConfig.SAMPLE_BASE_URL,
        )
    }
}
