package com.catchzoon.network.testing

import com.catchzoon.network.core.NetworkClient
import com.catchzoon.network.core.NetworkEngine
import com.catchzoon.network.core.NetworkFailure
import com.catchzoon.network.core.NetworkFailureCategory
import com.catchzoon.network.core.NetworkRawRequest
import com.catchzoon.network.core.NetworkRawResponse
import com.catchzoon.network.core.NetworkTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 单次脚本行为，可以返回响应、结构化失败或执行自定义挂起处理。 */
public sealed interface NetworkScenario {
    public data class Respond(val response: NetworkRawResponse) : NetworkScenario
    public data class Fail(val failure: NetworkFailure) : NetworkScenario
    public class Handle(public val block: suspend (NetworkRawRequest) -> NetworkRawResponse) : NetworkScenario
    public data class Delay(val delayMillis: Long, val next: NetworkScenario) : NetworkScenario {
        init {
            require(delayMillis in 0L..60_000L) { "测试延迟必须在 0..60000ms 之间" }
        }
    }
}

/** 可复用的响应 Fixture。 */
public data class NetworkFixture(
    val body: String,
    val statusCode: Int = 200,
    val headers: Map<String, String> = emptyMap(),
) {
    public fun scenario(): NetworkScenario = respond(body, statusCode, headers)
}

/**
 * 业务模块可复用的确定性测试引擎。
 *
 * 场景严格按入队顺序消费，所有请求都会被记录；调用 [cancelAll] 会取消自定义挂起场景。
 */
public class ScriptedNetworkEngine(
    scenarios: List<NetworkScenario> = emptyList(),
) : NetworkEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val pendingScenarios = ArrayDeque(scenarios)
    private val requests = mutableListOf<NetworkRawRequest>()

    override suspend fun execute(request: NetworkRawRequest): NetworkRawResponse {
        val task = scope.async {
            val scenario = mutex.withLock {
                requests += request
                pendingScenarios.removeFirstOrNull()
            } ?: throw NetworkTransportException(
                NetworkFailure(
                    code = "missing_mock_response",
                    category = NetworkFailureCategory.VALIDATION,
                    message = "测试引擎没有可用场景",
                ),
            )
            scenario.execute(request)
        }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            task.cancel()
            throw cancelled
        }
    }

    override fun cancelAll() {
        scope.coroutineContext.cancelChildren()
    }

    /** 在测试过程中继续追加场景。 */
    public suspend fun enqueue(scenario: NetworkScenario) {
        mutex.withLock { pendingScenarios.addLast(scenario) }
    }

    /** 返回不可变请求快照，断言不会与在途请求竞争。 */
    public suspend fun recordedRequests(): List<NetworkRawRequest> = mutex.withLock { requests.toList() }

    /** 当前尚未消费的脚本数量。 */
    public suspend fun remainingScenarios(): Int = mutex.withLock { pendingScenarios.size }

    /** 快速创建使用该引擎的客户端。 */
    public fun client(configure: NetworkClient.Builder.() -> Unit = {}): NetworkClient =
        NetworkClient.Builder(this).apply(configure).build()
}

/** 创建常用成功响应场景。 */
public fun respond(
    body: String,
    statusCode: Int = 200,
    headers: Map<String, String> = emptyMap(),
): NetworkScenario = NetworkScenario.Respond(NetworkRawResponse(statusCode, body, headers))

/** 创建结构化失败场景。 */
public fun fail(
    code: String,
    category: NetworkFailureCategory = NetworkFailureCategory.CONNECTIVITY,
    retryable: Boolean = false,
): NetworkScenario = NetworkScenario.Fail(NetworkFailure(code, category, retryable = retryable))

/** 创建带确定性延迟的场景。 */
public fun delayed(delayMillis: Long, next: NetworkScenario): NetworkScenario = NetworkScenario.Delay(delayMillis, next)

/** 创建标准离线失败。 */
public fun offline(): NetworkScenario = fail("network_offline", NetworkFailureCategory.CONNECTIVITY, retryable = true)

/** 创建标准超时失败。 */
public fun timeout(): NetworkScenario = fail("request_timeout", NetworkFailureCategory.TIMEOUT, retryable = true)

private suspend fun NetworkScenario.execute(request: NetworkRawRequest): NetworkRawResponse = when (this) {
    is NetworkScenario.Respond -> response
    is NetworkScenario.Fail -> throw NetworkTransportException(failure)
    is NetworkScenario.Handle -> block(request)
    is NetworkScenario.Delay -> {
        delay(delayMillis)
        next.execute(request)
    }
}
