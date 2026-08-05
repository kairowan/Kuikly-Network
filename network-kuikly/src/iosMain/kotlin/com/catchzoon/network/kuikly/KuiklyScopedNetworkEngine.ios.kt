package com.catchzoon.network.kuikly

import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkTlsPolicy
import com.catchzoon.network.platform.createPlatformNetworkEngine
import com.tencent.kuikly.com_tencent_kuikly_ScheduleContextTask
import com.tencent.kuikly.core.pager.Pager
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.coroutines.suspendCancellableCoroutine

/** iOS Kuikly 页面继续使用 NSURLSession 引擎。 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun createKuiklyScopedNetworkEngine(
    pager: Pager,
    baseUrl: String,
    tlsPolicy: NetworkTlsPolicy,
): NetworkEngine = KuiklyContextNetworkEngine(createPlatformNetworkEngine(baseUrl, tlsPolicy))

private class KuiklyContextNetworkEngine(
    private val delegate: NetworkEngine,
) : NetworkEngine {
    override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
        val result = try {
            Result.success(delegate.execute(request))
        } catch (error: Throwable) {
            Result.failure(error)
        }
        return resumeOnKuiklyContext(result)
    }

    override fun cancelAll() = delegate.cancelAll()
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun <T> resumeOnKuiklyContext(result: Result<T>): T =
    suspendCancellableCoroutine { continuation ->
        val callback = StableRef.create {
            if (continuation.isActive) continuation.resumeWith(result)
        }
        val token = callback.asCPointer().rawValue.toLong().toString()
        com_tencent_kuikly_ScheduleContextTask(token, resumeKuiklyContextTask)
    }

@OptIn(ExperimentalForeignApi::class)
private val resumeKuiklyContextTask = staticCFunction { token: CPointer<ByteVar>? ->
    val callback = token
        ?.toKString()
        ?.toLongOrNull()
        ?.toCPointer<COpaque>()
        ?.asStableRef<() -> Unit>()
        ?: return@staticCFunction
    try {
        callback.get().invoke()
    } finally {
        callback.dispose()
    }
}
