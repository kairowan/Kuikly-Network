package com.catchzoon.network.ksp

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Variance

internal data class MethodContract(
    val name: String,
    val isSuspend: Boolean,
    val returnType: String,
    val responseType: String,
    val returnKind: ReturnKind,
    val httpMethod: String,
    val path: String,
    val parameters: List<ParameterContract>,
    val fixedHeaders: List<Pair<String, String>>,
    val timeoutSeconds: Int?,
    val responseLimit: Long?,
    val retry: RetryContract?,
    val cache: CacheContract?,
    val bodyMode: BodyMode,
    val dynamicUrlParameter: String?,
    val streaming: Boolean,
    val priority: String?,
    val tags: List<String>,
    val invalidationTags: List<String>,
    val redirects: RedirectContract?,
) {
    fun generate(gson: Boolean): String = buildString {
        append("override ")
        if (isSuspend) append("suspend ")
        append("fun `${name}`(")
        append(parameters.joinToString { "`${it.name}`: ${it.type}" })
        appendLine("): $returnType {")
        appendLine("    val requestPath = com.catchzoon.network.api.resolveAnnotatedNetworkUrl(")
        appendLine("        template = ${dynamicUrlParameter?.let { "`${it}`" } ?: path.quoted()},")
        appendLine("        pathValues = ${parameters.pathValues()},")
        appendLine("        queryValues = ${parameters.queryValues()},")
        appendLine("        allowAbsoluteUrl = ${dynamicUrlParameter != null},")
        appendLine("    )")
        appendLine("    val requestHeaders = ${headersExpression()}")
        requestBodyExpression()?.let { appendLine("    val encodedRequestBody = $it") }
        appendLine("    val requestOptions = com.catchzoon.network.core.NetworkRequestOptions(")
        appendLine("        headers = requestHeaders,")
        appendLine("        timeoutSeconds = ${timeoutSeconds ?: "null"},")
        appendLine("        maxResponseBytes = ${responseLimit?.let { "${it}L" } ?: "null"},")
        appendLine("        retryPolicy = ${retry?.expression() ?: "null"},")
        appendLine("        cachePolicy = ${cache?.expression() ?: "null"},")
        appendLine("        requestBody = ${if (requestBodyExpression() == null) "null" else "encodedRequestBody"},")
        appendLine("        priority = ${priority?.let { "com.catchzoon.network.core.NetworkPriority.$it" } ?: "null"},")
        appendLine("        tags = ${tags.setExpression()},")
        appendLine("        redirectPolicy = ${redirects?.expression() ?: "null"},")
        appendLine("        allowAbsoluteUrl = ${dynamicUrlParameter != null},")
        appendLine("        streamResponse = $streaming,")
        appendLine("        cacheTags = ${(cache?.tags ?: emptyList()).setExpression()},")
        appendLine("        invalidateCacheTags = ${invalidationTags.setExpression()},")
        appendLine("    )")
        appendLine(generateCall(gson).prependIndent("    "))
        appendLine("    ${returnExpression()}")
        append("}")
    }

    private fun generateCall(gson: Boolean): String {
        val body = parameters.singleOrNull { it.kind == ParameterKind.BODY }
        val requestType = body?.type ?: "kotlin.Unit"
        val requestValue = body?.let { "`${it.name}`" } ?: "kotlin.Unit"
        if (streaming) return buildString {
            appendLine("val networkCall = client.byteCall(")
            appendLine("    method = com.catchzoon.network.core.NetworkMethod.$httpMethod,")
            appendLine("    path = requestPath,")
            appendLine("    options = requestOptions,")
            append(")")
        }
        if (bodyMode != BodyMode.JSON) return buildString {
            appendLine("val networkCall = client.encodedCall(")
            appendLine("    method = com.catchzoon.network.core.NetworkMethod.$httpMethod,")
            appendLine("    path = requestPath,")
            appendLine("    body = encodedRequestBody,")
            if (gson) {
                appendLine("    responseDecoder = com.catchzoon.network.api.NetworkDecoder { value ->")
                appendLine("        gson.fromJson(value, object : com.google.gson.reflect.TypeToken<$responseType>() {}.type)")
                appendLine("    },")
            } else {
                appendLine("    responseDeserializer = kotlinx.serialization.serializer<$responseType>(),")
            }
            appendLine("    options = requestOptions,")
            if (responseType == "kotlin.Unit") appendLine("    emptyResponseFactory = { kotlin.Unit },")
            append(")")
        }
        if (!gson) return buildString {
            appendLine("val networkCall = client.typedCall<$requestType, $responseType>(")
            appendLine("    method = com.catchzoon.network.core.NetworkMethod.$httpMethod,")
            appendLine("    path = requestPath,")
            appendLine("    request = $requestValue,")
            appendLine("    requestSerializer = ${if (body == null) "null" else "kotlinx.serialization.serializer<$requestType>()"},")
            appendLine("    responseDeserializer = kotlinx.serialization.serializer<$responseType>(),")
            appendLine("    options = requestOptions,")
            if (responseType == "kotlin.Unit") appendLine("    emptyResponseFactory = { kotlin.Unit },")
            append(")")
        }
        return buildString {
            appendLine("val networkCall = client.call(")
            appendLine("    endpoint = com.catchzoon.network.api.NetworkEndpoint<$requestType, $responseType>(")
            appendLine("        method = com.catchzoon.network.core.NetworkMethod.$httpMethod,")
            appendLine("        path = { requestPath },")
            appendLine("        requestEncoder = com.catchzoon.network.api.NetworkEncoder { value ->")
            appendLine("            ${if (body == null) "\"\"" else "gson.toJson(value, object : com.google.gson.reflect.TypeToken<$requestType>() {}.type)"}")
            appendLine("        },")
            appendLine("        responseDecoder = com.catchzoon.network.api.NetworkDecoder { value ->")
            appendLine("            gson.fromJson(value, object : com.google.gson.reflect.TypeToken<$responseType>() {}.type)")
            appendLine("        },")
            if (responseType == "kotlin.Unit") appendLine("        emptyResponseFactory = { kotlin.Unit },")
            appendLine("    ),")
            appendLine("    request = $requestValue,")
            appendLine("    options = requestOptions,")
            append(")")
        }
    }

    private fun returnExpression(): String = when (returnKind) {
        ReturnKind.CALL -> "return networkCall"
        ReturnKind.RESULT -> "return networkCall.await()"
        ReturnKind.FLOW -> "return networkCall.asFlow()"
        ReturnKind.DIRECT -> "return when (val result = networkCall.await()) {\n" +
            "        is com.catchzoon.network.core.NetworkResult.Success -> result.data\n" +
            "        is com.catchzoon.network.core.NetworkResult.Failure -> throw com.catchzoon.network.core.NetworkRequestException(result.error)\n" +
            "    }"
    }

    private fun headersExpression(): String {
        val values = buildList {
            val hasBody = parameters.any { it.kind == ParameterKind.BODY }
            val declaresContentType = fixedHeaders.any { (name, _) -> name.equals("Content-Type", ignoreCase = true) } ||
                parameters.any {
                    it.kind == ParameterKind.HEADER && it.wireName.equals("Content-Type", ignoreCase = true)
                }
            if (hasBody && !declaresContentType) add("put(\"Content-Type\", \"application/json; charset=utf-8\")")
            fixedHeaders.forEach { (name, value) -> add("put(${name.quoted()}, ${value.quoted()})") }
            parameters.filter {
                it.kind == ParameterKind.HEADER || it.kind == ParameterKind.IDEMPOTENCY_KEY ||
                    it.kind == ParameterKind.REQUEST_ID
            }.forEach { parameter ->
                val reference = "`${parameter.name}`"
                add(
                    if (parameter.nullable) "$reference?.toString()?.let { put(${parameter.wireName.quoted()}, it) }"
                    else if (parameter.kind == ParameterKind.IDEMPOTENCY_KEY || parameter.kind == ParameterKind.REQUEST_ID) {
                        "put(${parameter.wireName.quoted()}, $reference.toString().also { " +
                            "require(it.isNotBlank() && it.length <= 191) { \"请求标识长度必须在 1..191 之间\" } })"
                    } else {
                        "put(${parameter.wireName.quoted()}, $reference.toString())"
                    },
                )
            }
            parameters.filter { it.kind == ParameterKind.HEADER_MAP }.forEach { parameter ->
                add("`${parameter.name}`.forEach { (name, value) -> value?.let { put(name, it) } }")
            }
        }
        return if (values.isEmpty()) "emptyMap<String, String>()" else "buildMap { ${values.joinToString("; ")} }"
    }

    private fun requestBodyExpression(): String? = when (bodyMode) {
        BodyMode.JSON -> null
        BodyMode.FORM -> "com.catchzoon.network.core.networkFormBody(${parameters.formValues()})"
        BodyMode.MULTIPART -> "com.catchzoon.network.core.networkMultipartBody(${parameters.multipartValues()})"
    }
}

internal data class ParameterContract(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val kind: ParameterKind,
    val wireName: String,
    val fileName: String,
    val contentType: String,
)

internal data class RetryContract(
    val maxAttempts: Int,
    val initialDelayMillis: Long,
    val maxDelayMillis: Long,
    val multiplier: Double,
    val jitterRatio: Double,
    val retryUnsafeMethods: Boolean,
) {
    fun isValid(): Boolean = maxAttempts in 1..6 && initialDelayMillis >= 0L &&
        maxDelayMillis >= initialDelayMillis && multiplier >= 1.0 && jitterRatio in 0.0..1.0

    fun expression(): String = "com.catchzoon.network.core.NetworkRetryPolicy(" +
        "maxAttempts = $maxAttempts, initialDelayMillis = ${initialDelayMillis}L, " +
        "maxDelayMillis = ${maxDelayMillis}L, multiplier = $multiplier, jitterRatio = $jitterRatio, " +
        "retryUnsafeMethods = $retryUnsafeMethods)"
}

internal data class CacheContract(
    val maxAgeSeconds: Int,
    val staleIfErrorSeconds: Int,
    val mode: String,
    val staleWhileRevalidateSeconds: Int,
    val tags: List<String>,
) {
    fun isValid(): Boolean = maxAgeSeconds in 1..86_400 && staleIfErrorSeconds in 0..604_800 &&
        staleWhileRevalidateSeconds in 0..604_800

    fun expression(): String = "com.catchzoon.network.core.NetworkCachePolicy(" +
        "maxAgeSeconds = $maxAgeSeconds, staleIfErrorSeconds = $staleIfErrorSeconds, " +
        "mode = com.catchzoon.network.core.NetworkCacheMode.$mode, " +
        "staleWhileRevalidateSeconds = $staleWhileRevalidateSeconds)"
}

internal data class RedirectContract(
    val enabled: Boolean,
    val maxRedirects: Int,
    val allowCrossOrigin: Boolean,
) {
    fun expression(): String = "com.catchzoon.network.core.NetworkRedirectPolicy(" +
        "enabled = $enabled, maxRedirects = $maxRedirects, allowCrossOrigin = $allowCrossOrigin)"
}

internal data class ReturnContract(val kind: ReturnKind, val responseType: KSType)

internal enum class ReturnKind { CALL, RESULT, FLOW, DIRECT }

internal enum class ParameterKind {
    PATH,
    URL,
    QUERY,
    QUERY_MAP,
    HEADER,
    HEADER_MAP,
    BODY,
    FIELD,
    FIELD_MAP,
    PART,
    PART_MAP,
    IDEMPOTENCY_KEY,
    REQUEST_ID,
}

internal enum class BodyMode { JSON, FORM, MULTIPART }

internal fun KSType.returnContract(isSuspend: Boolean): ReturnContract? {
    val declarationName = declaration.qualifiedName?.asString()
    if (declarationName == NETWORK_CALL) {
        if (isSuspend) return null
        return arguments.singleOrNull()?.type?.resolve()?.let { ReturnContract(ReturnKind.CALL, it) }
    }
    if (declarationName == NETWORK_RESULT) {
        if (!isSuspend) return null
        return arguments.singleOrNull()?.type?.resolve()?.let { ReturnContract(ReturnKind.RESULT, it) }
    }
    if (declarationName == FLOW) {
        if (isSuspend) return null
        val state = arguments.singleOrNull()?.type?.resolve() ?: return null
        if (state.declaration.qualifiedName?.asString() != NETWORK_STATE) return null
        return state.arguments.singleOrNull()?.type?.resolve()?.let { ReturnContract(ReturnKind.FLOW, it) }
    }
    return if (isSuspend) ReturnContract(ReturnKind.DIRECT, this) else null
}

internal fun KSType.render(): String {
    val rawName = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
    val genericTypes = arguments.takeIf(List<*>::isNotEmpty)?.joinToString(prefix = "<", postfix = ">") { argument ->
        if (argument.variance == Variance.STAR || argument.type == null) "*" else argument.type!!.resolve().render()
    }.orEmpty()
    return rawName + genericTypes + if (nullability == Nullability.NULLABLE) "?" else ""
}

internal fun KSType.isStringMap(): Boolean =
    declaration.qualifiedName?.asString() == "kotlin.collections.Map" && arguments.size == 2 &&
        arguments[0].type?.resolve()?.declaration?.qualifiedName?.asString() == "kotlin.String" &&
        arguments[1].type?.resolve()?.declaration?.qualifiedName?.asString() == "kotlin.String"

internal fun KSAnnotated.annotation(name: String): KSAnnotation? = annotations.firstOrNull {
    it.annotationType.resolve().declaration.qualifiedName?.asString() == name
}

internal fun KSAnnotation.argument(name: String): Any? = arguments.firstOrNull {
    it.name?.asString() == name
}?.value

internal fun Any?.asStringList(): List<String> = when (this) {
    is List<*> -> filterIsInstance<String>()
    is String -> listOf(this)
    else -> emptyList()
}

private fun List<ParameterContract>.pathValues(): String {
    val values = filter { it.kind == ParameterKind.PATH }
        .joinToString { "${it.wireName.quoted()} to `${it.name}`.toString()" }
    return if (values.isEmpty()) "emptyMap()" else "mapOf($values)"
}

private fun List<ParameterContract>.queryValues(): String {
    val parameters = this
    val entries = buildList {
        parameters.filter { it.kind == ParameterKind.QUERY }.forEach { parameter ->
            val value = if (parameter.nullable) "`${parameter.name}`?.toString()" else "`${parameter.name}`.toString()"
            add("add(${parameter.wireName.quoted()} to $value)")
        }
        parameters.filter { it.kind == ParameterKind.QUERY_MAP }.forEach { parameter ->
            add("`${parameter.name}`.forEach { (name, value) -> add(name to value) }")
        }
    }
    return if (entries.isEmpty()) "emptyList()" else "buildList { ${entries.joinToString("; ")} }"
}

private fun List<ParameterContract>.formValues(): String {
    val parameters = this
    val entries = buildList {
        parameters.filter { it.kind == ParameterKind.FIELD }.forEach { parameter ->
            val reference = "`${parameter.name}`"
            add("add(${parameter.wireName.quoted()} to ${if (parameter.nullable) "$reference?.toString()" else "$reference.toString()"})")
        }
        parameters.filter { it.kind == ParameterKind.FIELD_MAP }.forEach { parameter ->
            add("`${parameter.name}`.forEach { (name, value) -> add(name to value) }")
        }
    }
    return if (entries.isEmpty()) "emptyList()" else "buildList { ${entries.joinToString("; ")} }"
}

private fun List<ParameterContract>.multipartValues(): String {
    val parameters = this
    val entries = buildList {
        parameters.filter { it.kind == ParameterKind.PART }.forEach { parameter ->
            val value = "com.catchzoon.network.core.networkMultipartPart(" +
                "name = ${parameter.wireName.quoted()}, value = `${parameter.name}`, " +
                "fileName = ${parameter.fileName.quoted()}, contentType = ${parameter.contentType.quoted()})"
            add(if (parameter.nullable) "`${parameter.name}`?.let { add($value) }" else "add($value)")
        }
        parameters.filter { it.kind == ParameterKind.PART_MAP }.forEach { parameter ->
            add("`${parameter.name}`.forEach { (name, value) -> value?.let { add(com.catchzoon.network.core.NetworkMultipartPart.text(name, it)) } }")
        }
    }
    return if (entries.isEmpty()) "emptyList()" else "buildList { ${entries.joinToString("; ")} }"
}

private fun List<String>.setExpression(): String =
    if (isEmpty()) "emptySet()" else joinToString(prefix = "setOf(", postfix = ")") { it.quoted() }

private fun String.quoted(): String = buildString {
    append('"')
    this@quoted.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

internal const val NETWORK_SERVICE = "com.catchzoon.network.annotation.NetworkService"
internal const val DEFAULT_CLIENT_NAME = "default"
internal val CLIENT_NAME = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
internal const val HEADERS = "com.catchzoon.network.annotation.Headers"
internal const val TIMEOUT = "com.catchzoon.network.annotation.Timeout"
internal const val RETRY = "com.catchzoon.network.annotation.Retry"
internal const val RESPONSE_LIMIT = "com.catchzoon.network.annotation.ResponseLimit"
internal const val CACHE = "com.catchzoon.network.annotation.Cache"
internal const val FORM_URL_ENCODED = "com.catchzoon.network.annotation.FormUrlEncoded"
internal const val MULTIPART = "com.catchzoon.network.annotation.Multipart"
internal const val STREAMING = "com.catchzoon.network.annotation.Streaming"
internal const val PRIORITY = "com.catchzoon.network.annotation.Priority"
internal const val TAGS = "com.catchzoon.network.annotation.Tags"
internal const val REDIRECTS = "com.catchzoon.network.annotation.Redirects"
internal const val INVALIDATE_CACHE = "com.catchzoon.network.annotation.InvalidateCache"
private const val NETWORK_CALL = "com.catchzoon.network.core.NetworkCall"
private const val NETWORK_RESULT = "com.catchzoon.network.core.NetworkResult"
private const val NETWORK_STATE = "com.catchzoon.network.core.NetworkState"
private const val FLOW = "kotlinx.coroutines.flow.Flow"
internal const val MAX_RESPONSE_BYTES = 20L * 1024L * 1024L
internal val ROUTE_PARAMETER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}")
internal val ROUTE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
internal val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
internal val NETWORK_TAG = Regex("[A-Za-z0-9_.:/-]{1,64}")
internal val FORM_OR_PART_KINDS = setOf(
    ParameterKind.FIELD,
    ParameterKind.FIELD_MAP,
    ParameterKind.PART,
    ParameterKind.PART_MAP,
)
internal val BODY_PARAMETER_KINDS = FORM_OR_PART_KINDS + ParameterKind.BODY
internal val MULTIPART_TYPES = setOf(
    "kotlin.String",
    "kotlin.ByteArray",
    "com.catchzoon.network.core.NetworkMultipartPart",
)

internal val HTTP_ANNOTATIONS = mapOf(
    "com.catchzoon.network.annotation.GET" to "GET",
    "com.catchzoon.network.annotation.POST" to "POST",
    "com.catchzoon.network.annotation.PUT" to "PUT",
    "com.catchzoon.network.annotation.PATCH" to "PATCH",
    "com.catchzoon.network.annotation.DELETE" to "DELETE",
    "com.catchzoon.network.annotation.HEAD" to "HEAD",
    "com.catchzoon.network.annotation.OPTIONS" to "OPTIONS",
)

internal val PARAMETER_ANNOTATIONS = mapOf(
    "com.catchzoon.network.annotation.Path" to ParameterKind.PATH,
    "com.catchzoon.network.annotation.Url" to ParameterKind.URL,
    "com.catchzoon.network.annotation.Query" to ParameterKind.QUERY,
    "com.catchzoon.network.annotation.QueryMap" to ParameterKind.QUERY_MAP,
    "com.catchzoon.network.annotation.Header" to ParameterKind.HEADER,
    "com.catchzoon.network.annotation.HeaderMap" to ParameterKind.HEADER_MAP,
    "com.catchzoon.network.annotation.Body" to ParameterKind.BODY,
    "com.catchzoon.network.annotation.Field" to ParameterKind.FIELD,
    "com.catchzoon.network.annotation.FieldMap" to ParameterKind.FIELD_MAP,
    "com.catchzoon.network.annotation.Part" to ParameterKind.PART,
    "com.catchzoon.network.annotation.PartMap" to ParameterKind.PART_MAP,
    "com.catchzoon.network.annotation.IdempotencyKey" to ParameterKind.IDEMPOTENCY_KEY,
    "com.catchzoon.network.annotation.RequestId" to ParameterKind.REQUEST_ID,
)
