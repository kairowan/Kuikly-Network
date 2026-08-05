package com.catchzoon.network.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.random.Random

/**
 * 一个可重复执行、可组合的类型化网络调用。
 *
 * API 类返回 NetworkCall 后，调用方可按场景选择 await、asFlow、map、flatMap、recover 或 retry。
 */
public class NetworkCall<T> internal constructor(
    private val method: NetworkMethod,
    private val options: NetworkRequestOptions,
    private val retryCondition: (NetworkFailure) -> Boolean = { true },
    private val executeOnce: suspend (NetworkRequestOptions) -> NetworkResult<T>,
) {
    /** 在当前协程中执行，并应用幂等安全的重试策略。 */
    public suspend fun await(): NetworkResult<T> {
        var attempt = 1
        while (true) {
            when (val result = executeOnce(options).withAttempt(attempt)) {
                is NetworkResult.Success -> return result
                is NetworkResult.Failure -> {
                    if (!shouldRetry(result.error, attempt)) return result
                    if (options.retryBudget?.tryAcquire() == false) return result
                    delay(retryDelayMillis(attempt, result.error))
                    attempt++
                }
            }
        }
    }

    /** 转换成功数据，失败保持原样。 */
    public fun <R> map(transform: (T) -> R): NetworkCall<R> = NetworkCall(method, options, retryCondition) { effective ->
        when (val result = executeOnce(effective)) {
            is NetworkResult.Success -> try {
                NetworkResult.Success(
                    data = transform(result.data),
                    statusCode = result.statusCode,
                    headers = result.headers,
                    requestId = result.requestId,
                    durationMillis = result.durationMillis,
                    attempt = result.attempt,
                    source = result.source,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NetworkResult.Failure(
                    NetworkFailure(
                        code = "mapping_error",
                        category = NetworkFailureCategory.SERIALIZATION,
                        message = "成功数据转换失败",
                        statusCode = result.statusCode,
                        requestId = result.requestId,
                        attempt = result.attempt,
                    ),
                )
            }
            is NetworkResult.Failure -> result
        }
    }

    /** 使用挂起函数转换成功数据，适合串接数据库或其他异步数据源。 */
    public fun <R> mapSuspend(transform: suspend (T) -> R): NetworkCall<R> =
        NetworkCall(method, options, retryCondition) { effective ->
            when (val result = executeOnce(effective)) {
                is NetworkResult.Success -> try {
                    result.mapData(transform(result.data))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    result.mappingFailure()
                }
                is NetworkResult.Failure -> result
            }
        }

    /** 成功后继续执行下一个网络调用。 */
    public fun <R> flatMap(transform: (T) -> NetworkCall<R>): NetworkCall<R> =
        NetworkCall(method, options, retryCondition) { effective ->
            when (val result = executeOnce(effective)) {
                is NetworkResult.Success -> try {
                    transform(result.data).await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    NetworkResult.Failure(
                        NetworkFailure(
                            code = "operator_error",
                            message = "后续调用创建失败",
                            requestId = result.requestId,
                        ),
                    )
                }
                is NetworkResult.Failure -> result
            }
        }

    /** 转换结构化失败，成功结果保持不变。 */
    public fun mapFailure(transform: (NetworkFailure) -> NetworkFailure): NetworkCall<T> =
        NetworkCall(method, options, retryCondition) { effective ->
            when (val result = executeOnce(effective)) {
                is NetworkResult.Success -> result
                is NetworkResult.Failure -> try {
                    NetworkResult.Failure(transform(result.error))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    NetworkResult.Failure(
                        NetworkFailure(
                            code = "operator_error",
                            message = "失败数据转换失败",
                            requestId = result.error.requestId,
                        ),
                    )
                }
            }
        }

    /** 校验成功数据，不满足条件时转换为调用方定义的结构化失败。 */
    public fun validate(
        predicate: (T) -> Boolean,
        failure: (T) -> NetworkFailure,
    ): NetworkCall<T> = NetworkCall(method, options, retryCondition) { effective ->
        when (val result = executeOnce(effective)) {
            is NetworkResult.Failure -> result
            is NetworkResult.Success -> try {
                if (predicate(result.data)) result else NetworkResult.Failure(
                    failure(result.data).withFallbackRequestId(result.requestId),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result.mappingFailure("成功数据校验失败")
            }
        }
    }

    /** 把指定失败恢复为本地数据；返回 null 表示继续保留失败。 */
    public fun recover(transform: (NetworkFailure) -> T?): NetworkCall<T> =
        NetworkCall(method, options, NEVER_RETRY) { effective ->
            when (val result = executeWithRetry(effective)) {
                is NetworkResult.Success -> result
                is NetworkResult.Failure -> try {
                    transform(result.error)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }?.let { fallback ->
                    NetworkResult.Success(
                        data = fallback,
                        statusCode = result.error.statusCode ?: 0,
                        headers = emptyMap(),
                        requestId = result.error.requestId,
                        attempt = result.error.attempt,
                        source = NetworkResponseSource.LOCAL_FALLBACK,
                    )
                } ?: result
            }
        }

    /** 失败后切换到另一个调用；返回 null 时保留原始失败。 */
    public fun recoverWith(transform: (NetworkFailure) -> NetworkCall<T>?): NetworkCall<T> =
        NetworkCall(method, options, NEVER_RETRY) { effective ->
            when (val result = executeWithRetry(effective)) {
                is NetworkResult.Success -> result
                is NetworkResult.Failure -> try {
                    transform(result.error)?.await() ?: result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    result
                }
            }
        }

    /** 当前调用失败时执行备用调用。 */
    public fun fallbackTo(call: NetworkCall<T>): NetworkCall<T> = recoverWith { call }

    /** 成功时执行副作用，适合轻量缓存或埋点，不改变数据。 */
    public fun onSuccess(action: (T) -> Unit): NetworkCall<T> =
        NetworkCall(method, options, retryCondition) { effective ->
            when (val result = executeOnce(effective)) {
                is NetworkResult.Failure -> result
                is NetworkResult.Success -> try {
                    action(result.data)
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    NetworkResult.Failure(
                        NetworkFailure(
                            code = "operator_error",
                            message = "成功回调执行失败",
                            requestId = result.requestId,
                        ),
                    )
                }
            }
        }

    /** 失败时执行副作用，适合统一提示或业务日志，不吞掉失败。 */
    public fun onFailure(action: (NetworkFailure) -> Unit): NetworkCall<T> =
        NetworkCall(method, options, NEVER_RETRY) { effective ->
            executeWithRetry(effective).also { result ->
                if (result is NetworkResult.Failure) {
                    try {
                        action(result.error)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 失败监听是旁路能力，不能覆盖原始网络失败。
                    }
                }
            }
        }

    /** 返回使用新重试规则的不可变调用，不修改原对象。 */
    public fun retry(policy: NetworkRetryPolicy): NetworkCall<T> =
        NetworkCall(method, options.copy(retryPolicy = policy), retryCondition, executeOnce)

    /** 使用常用指数退避参数配置重试。 */
    public fun retry(
        maxAttempts: Int,
        initialDelayMillis: Long = 300L,
        maxDelayMillis: Long = 5_000L,
        multiplier: Double = 2.0,
        jitterRatio: Double = 0.2,
        retryUnsafeMethods: Boolean = false,
    ): NetworkCall<T> = retry(
        NetworkRetryPolicy(
            maxAttempts = maxAttempts,
            initialDelayMillis = initialDelayMillis,
            maxDelayMillis = maxDelayMillis,
            multiplier = multiplier,
            jitterRatio = jitterRatio,
            retryUnsafeMethods = retryUnsafeMethods,
        ),
    )

    /** 仅在失败满足业务条件时执行既定重试策略。 */
    public fun retryWhen(
        policy: NetworkRetryPolicy,
        predicate: (NetworkFailure) -> Boolean,
    ): NetworkCall<T> = NetworkCall(method, options.copy(retryPolicy = policy), predicate, executeOnce)

    /** 禁止当前调用自动重试。 */
    public fun noRetry(): NetworkCall<T> = retry(NetworkRetryPolicy.none())

    /** 返回使用新超时的不可变调用。 */
    public fun timeout(seconds: Int): NetworkCall<T> =
        NetworkCall(method, options.copy(timeoutSeconds = seconds), retryCondition, executeOnce)

    /** 限制单次响应正文大小，避免异常响应占满内存。 */
    public fun responseLimit(bytes: Long): NetworkCall<T> =
        NetworkCall(method, options.copy(maxResponseBytes = bytes), retryCondition, executeOnce)

    /** 为当前 GET 调用配置响应缓存；非 GET 请求不会被缓存。 */
    public fun cache(policy: NetworkCachePolicy): NetworkCall<T> =
        NetworkCall(method, options.copy(cachePolicy = policy), retryCondition, executeOnce)

    /** 在有效期内优先使用缓存。 */
    public fun cacheFirst(maxAgeSeconds: Int): NetworkCall<T> = cache(NetworkCachePolicy.cacheFirst(maxAgeSeconds))

    /** 在线失败时允许使用过期缓存。 */
    public fun staleIfError(maxAgeSeconds: Int, staleIfErrorSeconds: Int): NetworkCall<T> =
        cache(NetworkCachePolicy.staleIfError(maxAgeSeconds, staleIfErrorSeconds))

    /** 在线优先，失败时使用缓存。 */
    public fun networkFirst(maxAgeSeconds: Int, staleIfErrorSeconds: Int = 0): NetworkCall<T> =
        cache(NetworkCachePolicy.networkFirst(maxAgeSeconds, staleIfErrorSeconds))

    /** 立即返回缓存并在后台刷新。 */
    public fun staleWhileRevalidate(maxAgeSeconds: Int, staleWhileRevalidateSeconds: Int): NetworkCall<T> =
        cache(NetworkCachePolicy.staleWhileRevalidate(maxAgeSeconds, staleWhileRevalidateSeconds))

    /** 只允许读取缓存。 */
    public fun cacheOnly(maxAgeSeconds: Int): NetworkCall<T> = cache(NetworkCachePolicy.cacheOnly(maxAgeSeconds))

    /** 禁止当前调用使用响应缓存。 */
    public fun noCache(): NetworkCall<T> = cache(NetworkCachePolicy.none())

    /** 设置请求调度优先级。 */
    public fun priority(value: NetworkPriority): NetworkCall<T> =
        NetworkCall(method, options.copy(priority = value), retryCondition, executeOnce)

    /** 添加业务或调试标签，不会进入 URL 和请求头。 */
    public fun tag(value: String): NetworkCall<T> = tags(setOf(value))

    /** 添加多个请求标签。 */
    public fun tags(values: Set<String>): NetworkCall<T> =
        NetworkCall(method, options.copy(tags = options.tags + values), retryCondition, executeOnce)

    /** 覆盖单次请求的重定向策略。 */
    public fun redirects(policy: NetworkRedirectPolicy): NetworkCall<T> =
        NetworkCall(method, options.copy(redirectPolicy = policy), retryCondition, executeOnce)

    /** 监听上传和下载进度。 */
    public fun progress(listener: NetworkProgressListener): NetworkCall<T> =
        NetworkCall(method, options.copy(progressListener = listener), retryCondition, executeOnce)

    /** 为响应写入缓存标签，便于后续精确失效。 */
    public fun cacheTags(vararg values: String): NetworkCall<T> =
        NetworkCall(method, options.copy(cacheTags = options.cacheTags + values), retryCondition, executeOnce)

    /** 写请求成功后只失效指定缓存标签。 */
    public fun invalidateCacheTags(vararg values: String): NetworkCall<T> =
        NetworkCall(method, options.copy(invalidateCacheTags = options.invalidateCacheTags + values), retryCondition, executeOnce)

    /** 为当前调用配置共享重试预算。 */
    public fun retryBudget(budget: NetworkRetryBudget): NetworkCall<T> =
        NetworkCall(method, options.copy(retryBudget = budget), retryCondition, executeOnce)

    /** 添加或覆盖单个请求头。 */
    public fun header(name: String, value: String): NetworkCall<T> = headers(mapOf(name to value))

    /** 添加或覆盖多个请求头，原调用保持不可变。 */
    public fun headers(values: Map<String, String>): NetworkCall<T> {
        require(values.keys.all(String::isNotBlank)) { "请求头名称不能为空" }
        require(values.all { (name, value) ->
            '\r' !in name && '\n' !in name && '\r' !in value && '\n' !in value
        }) { "请求头不能包含换行符" }
        return NetworkCall(
            method,
            options.copy(headers = options.headers.putAllIgnoringCase(values)),
            retryCondition,
            executeOnce,
        )
    }

    /** 为写请求设置幂等键，并允许默认重试策略安全识别该请求。 */
    public fun idempotencyKey(value: String): NetworkCall<T> {
        require(value.isNotBlank() && value.length <= 191) { "幂等键长度必须在 1..191 之间" }
        return NetworkCall(
            method,
            options.copy(headers = options.headers.putAllIgnoringCase(mapOf("Idempotency-Key" to value))),
            retryCondition,
            executeOnce,
        )
    }

    /** 执行并直接返回成功数据；失败时抛出携带结构化原因的异常。 */
    public suspend fun awaitData(): T = when (val result = await()) {
        is NetworkResult.Success -> result.data
        is NetworkResult.Failure -> throw NetworkRequestException(result.error)
    }

    /** 把最终结果折叠为业务类型，同时保留成功元数据和结构化失败。 */
    public suspend fun <R> fold(
        onSuccess: (NetworkResult.Success<T>) -> R,
        onFailure: (NetworkFailure) -> R,
    ): R = when (val result = await()) {
        is NetworkResult.Success -> onSuccess(result)
        is NetworkResult.Failure -> onFailure(result.error)
    }

    /** 转换为 Loading → Success/Error 的冷 Flow，取消收集会取消当前协程请求。 */
    public fun asFlow(): Flow<NetworkState<T>> = flow {
        emit(NetworkState.Loading)
        when (val result = await()) {
            is NetworkResult.Success -> emit(
                NetworkState.Success(
                    data = result.data,
                    statusCode = result.statusCode,
                    headers = result.headers,
                    requestId = result.requestId,
                    durationMillis = result.durationMillis,
                    attempt = result.attempt,
                    source = result.source,
                ),
            )
            is NetworkResult.Failure -> emit(NetworkState.Error(result.error))
        }
    }

    /** 转换为只发射一次 Success/Failure、不自动插入 Loading 的冷 Flow。 */
    public fun asResultFlow(): Flow<NetworkResult<T>> = flow { emit(await()) }

    /** 在指定作用域执行调用，返回 Job 供页面生命周期统一取消。 */
    public fun enqueue(
        scope: CoroutineScope,
        onSuccess: (T) -> Unit = {},
        onFailure: (NetworkFailure) -> Unit = {},
    ): Job = scope.launch {
        when (val result = await()) {
            is NetworkResult.Success -> onSuccess(result.data)
            is NetworkResult.Failure -> onFailure(result.error)
        }
    }

    private fun shouldRetry(failure: NetworkFailure, attempt: Int): Boolean {
        val policy = requireNotNull(options.retryPolicy)
        if (!failure.retryable || attempt >= policy.maxAttempts || !retryCondition.safelyAllows(failure)) {
            return false
        }
        if (policy.retryUnsafeMethods) return true
        return method in SAFE_RETRY_METHODS || options.headers.keys.any { it.equals("Idempotency-Key", ignoreCase = true) }
    }

    private fun retryDelayMillis(attempt: Int, failure: NetworkFailure): Long {
        val policy = requireNotNull(options.retryPolicy)
        val base = (policy.initialDelayMillis * policy.multiplier.pow((attempt - 1).toDouble()))
            .toLong()
            .coerceAtMost(policy.maxDelayMillis)
        val clientDelay = if (base == 0L || policy.jitterRatio == 0.0) {
            base
        } else {
            val jitter = (base * policy.jitterRatio * Random.nextDouble(-1.0, 1.0)).toLong()
            (base + jitter).coerceAtLeast(0L)
        }
        return maxOf(clientDelay, failure.retryAfterMillis).coerceAtMost(policy.maxDelayMillis)
    }

    private suspend fun executeWithRetry(effective: NetworkRequestOptions): NetworkResult<T> =
        NetworkCall(method, effective, retryCondition, executeOnce).await()
}

private fun <T, R> NetworkResult.Success<T>.mapData(data: R): NetworkResult.Success<R> = NetworkResult.Success(
    data = data,
    statusCode = statusCode,
    headers = headers,
    requestId = requestId,
    durationMillis = durationMillis,
    attempt = attempt,
    source = source,
)

private fun <T> NetworkResult.Success<T>.mappingFailure(message: String = "成功数据转换失败"): NetworkResult.Failure =
    NetworkResult.Failure(
        NetworkFailure(
            code = "mapping_error",
            category = NetworkFailureCategory.SERIALIZATION,
            message = message,
            statusCode = statusCode,
            requestId = requestId,
            attempt = attempt,
        ),
    )

private fun NetworkFailure.withFallbackRequestId(fallback: String): NetworkFailure =
    if (requestId.isNotEmpty() || fallback.isEmpty()) this else copy(requestId = fallback)

private fun ((NetworkFailure) -> Boolean).safelyAllows(failure: NetworkFailure): Boolean =
    try {
        invoke(failure)
    } catch (_: Exception) {
        false
    }

private fun Map<String, String>.putAllIgnoringCase(values: Map<String, String>): Map<String, String> = buildMap {
    putAll(this@putAllIgnoringCase)
    values.forEach { (name, value) ->
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
        put(name, value)
    }
}

private fun <T> NetworkResult<T>.withAttempt(attempt: Int): NetworkResult<T> = when (this) {
    is NetworkResult.Success -> copy(attempt = attempt)
    is NetworkResult.Failure -> NetworkResult.Failure(error.copy(attempt = attempt))
}

private val SAFE_RETRY_METHODS = setOf(NetworkMethod.GET, NetworkMethod.PUT, NetworkMethod.DELETE)
private val NEVER_RETRY: (NetworkFailure) -> Boolean = { false }
