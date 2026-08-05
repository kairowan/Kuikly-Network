package com.catchzoon.network.core

import com.catchzoon.network.api.networkUrl
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** 关键纯 Kotlin 热点的本地回归基准，不依赖设备和真实网络。 */
class NetworkHotPathBenchmarkTest {
    @Test
    fun urlAndFormEncodingBenchmark() {
        val iterations = 50_000
        var checksum = 0
        val mark = TimeSource.Monotonic.markNow()

        repeat(iterations) { index ->
            checksum += networkUrl("/v1/cards") {
                segment("物种-$index")
                query("locale", "zh-CN")
            }.length
            checksum += networkFormBody(listOf("keyword" to "雪豹-$index", "page" to index.toString()))
                .value.length
        }

        assertTrue(checksum > iterations)
        // ponytail: 当前只提供跨平台热点趋势值；需要稳定纳秒基线时再迁移到 kotlinx-benchmark。
        println("network-hot-path iterations=$iterations elapsed=${mark.elapsedNow()} checksum=$checksum")
    }
}
