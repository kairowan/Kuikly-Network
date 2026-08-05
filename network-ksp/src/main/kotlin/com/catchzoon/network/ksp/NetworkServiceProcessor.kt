package com.catchzoon.network.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.validate

/** 向 KSP 注册网络接口代码生成器。 */
public class NetworkServiceProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = NetworkServiceProcessor(
        codeGenerator = environment.codeGenerator,
        logger = environment.logger,
    )
}

private class NetworkServiceProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val generatedServices = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(NETWORK_SERVICE)
        val deferred = symbols.filterNot(KSAnnotated::validate).toList()
        symbols.filter(KSAnnotated::validate)
            .filterIsInstance<KSClassDeclaration>()
            .forEach(::generateService)
        return deferred
    }

    /** 校验单个接口，并生成基于 NetworkClient 的目标平台实现。 */
    private fun generateService(service: KSClassDeclaration) {
        val qualifiedName = service.qualifiedName?.asString() ?: return
        if (!generatedServices.add(qualifiedName)) return
        if (service.classKind != ClassKind.INTERFACE) {
            logger.error("@NetworkService 只能标记接口", service)
            return
        }
        if (service.parentDeclaration != null || service.typeParameters.isNotEmpty()) {
            logger.error("@NetworkService 仅支持非泛型的顶层接口", service)
            return
        }
        if (Modifier.PRIVATE in service.modifiers || Modifier.PROTECTED in service.modifiers) {
            logger.error("@NetworkService 接口至少需要 internal 可见性", service)
            return
        }
        val methods = service.declarations.filterIsInstance<KSFunctionDeclaration>().toList()
        if (methods.isEmpty()) {
            logger.error("@NetworkService 接口至少需要一个方法", service)
            return
        }
        val contracts = methods.mapNotNull(::parseMethod)
        if (contracts.size != methods.size) return

        val packageName = service.packageName.asString()
        val serviceName = service.simpleName.asString()
        val generatedName = "${serviceName}NetworkService"
        val visibility = if (Modifier.INTERNAL in service.modifiers) "internal" else "public"
        val serviceAnnotation = service.annotation(NETWORK_SERVICE)
        val serialization = serviceAnnotation
            ?.argument("serialization")
            ?.toString()
            ?.substringAfterLast('.')
            .orEmpty()
        val clientName = serviceAnnotation?.argument("client") as? String ?: DEFAULT_CLIENT_NAME
        if (!CLIENT_NAME.matches(clientName)) {
            logger.error("@NetworkService client 必须以字母开头，且只能使用字母、数字和 -_.，长度 1..64", service)
            return
        }
        val gson = serialization == "GSON"
        val source = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("private class $generatedName(")
            appendLine("    private val client: com.catchzoon.network.core.NetworkClient,")
            if (gson) appendLine("    private val gson: com.google.gson.Gson = com.google.gson.Gson(),")
            appendLine(") : $qualifiedName {")
            contracts.forEach { append(it.generate(gson).prependIndent("    ")).appendLine() }
            appendLine("}")
            appendLine()
            appendLine("$visibility fun com.catchzoon.network.core.NetworkClient.create$serviceName(): $qualifiedName =")
            appendLine("    $generatedName(this)")
            appendLine()
            appendLine("$visibility fun create$serviceName(clientName: String = \"$clientName\"): $qualifiedName =")
            appendLine("    com.catchzoon.network.core.NetworkClients.client(clientName).create$serviceName()")
        }
        val sourceFile = service.containingFile ?: return
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, sourceFile),
            packageName = packageName,
            fileName = "${serviceName}NetworkFactory",
        ).bufferedWriter().use { it.write(source) }
    }

    /** 把方法注解和类型签名转换为不依赖 KSP 的生成契约。 */
    private fun parseMethod(function: KSFunctionDeclaration): MethodContract? {
        if (function.typeParameters.isNotEmpty()) return function.error("网络接口方法暂不支持方法级泛型")
        val httpAnnotations = HTTP_ANNOTATIONS.mapNotNull { (annotation, method) ->
            function.annotation(annotation)?.let { method to it }
        }
        if (httpAnnotations.size != 1) return function.error("每个网络方法必须且只能声明一个 HTTP 注解")
        val (httpMethod, httpAnnotation) = httpAnnotations.single()
        val path = httpAnnotation.argument("value") as? String
            ?: return function.error("HTTP 注解缺少接口路径")
        val returnType = function.returnType?.resolve() ?: return function.error("网络方法必须声明返回类型")
        val returnContract = returnType.returnContract(Modifier.SUSPEND in function.modifiers)
            ?: return function.error("支持 suspend T、suspend NetworkResult<T>、NetworkCall<T> 或 Flow<NetworkState<T>>")
        val parameters = function.parameters.mapNotNull(::parseParameter)
        if (parameters.size != function.parameters.size) return null
        if (parameters.count { it.kind == ParameterKind.BODY } > 1) return function.error("每个方法最多声明一个 @Body")
        if (parameters.count { it.kind == ParameterKind.URL } > 1) return function.error("每个方法最多声明一个 @Url")
        if (parameters.count { it.kind == ParameterKind.IDEMPOTENCY_KEY } > 1) {
            return function.error("每个方法最多声明一个 @IdempotencyKey")
        }
        val formEncoded = function.annotation(FORM_URL_ENCODED) != null
        val multipart = function.annotation(MULTIPART) != null
        val streaming = function.annotation(STREAMING) != null
        if (formEncoded && multipart) return function.error("@FormUrlEncoded 和 @Multipart 不能同时使用")
        val bodyMode = when {
            formEncoded -> BodyMode.FORM
            multipart -> BodyMode.MULTIPART
            else -> BodyMode.JSON
        }
        val bodyKinds = parameters.map(ParameterContract::kind)
        if (bodyMode == BodyMode.FORM &&
            (bodyKinds.none { it == ParameterKind.FIELD || it == ParameterKind.FIELD_MAP } ||
                bodyKinds.any { it in setOf(ParameterKind.BODY, ParameterKind.PART, ParameterKind.PART_MAP) })
        ) return function.error("@FormUrlEncoded 只能包含 @Field 或 @FieldMap 正文参数")
        if (bodyMode == BodyMode.MULTIPART &&
            (bodyKinds.none { it == ParameterKind.PART || it == ParameterKind.PART_MAP } ||
                bodyKinds.any { it in setOf(ParameterKind.BODY, ParameterKind.FIELD, ParameterKind.FIELD_MAP) })
        ) return function.error("@Multipart 只能包含 @Part 或 @PartMap 正文参数")
        if (bodyMode == BodyMode.JSON && bodyKinds.any { it in FORM_OR_PART_KINDS }) {
            return function.error("@Field 需要 @FormUrlEncoded，@Part 需要 @Multipart")
        }
        if (httpMethod in setOf("GET", "HEAD") && bodyKinds.any { it in BODY_PARAMETER_KINDS }) {
            return function.error("$httpMethod 方法不能声明请求正文")
        }
        if (streaming && returnContract.responseType.declaration.qualifiedName?.asString() != "kotlin.ByteArray") {
            return function.error("@Streaming 的响应类型必须是 ByteArray")
        }
        val dynamicUrl = parameters.singleOrNull { it.kind == ParameterKind.URL }
        if (dynamicUrl != null) {
            if (path.isNotEmpty() || parameters.any { it.kind == ParameterKind.PATH }) {
                return function.error("使用 @Url 时 HTTP 注解路径必须为空且不能声明 @Path")
            }
        } else {
            val pathWithoutParameters = ROUTE_PARAMETER.replace(path, "")
            if (!path.startsWith('/') || path.contains('?') || path.contains('#') || path.contains("..") ||
                pathWithoutParameters.contains('{') || pathWithoutParameters.contains('}')
            ) {
                return function.error("接口路径必须是无查询参数的安全相对路径")
            }
            val declaredPathNames = parameters.filter { it.kind == ParameterKind.PATH }.map { it.wireName }.toSet()
            val templatePathNames = ROUTE_PARAMETER.findAll(path).map { it.groupValues[1] }.toSet()
            if (declaredPathNames != templatePathNames) {
                return function.error("接口路径占位符必须与 @Path 参数一一对应")
            }
        }

        val timeoutSeconds = function.annotation(TIMEOUT)?.argument("seconds") as? Int
        if (timeoutSeconds != null && timeoutSeconds !in 1..300) {
            return function.error("@Timeout 秒数必须在 1..300 之间")
        }
        val responseLimit = function.annotation(RESPONSE_LIMIT)?.argument("bytes") as? Long
        if (responseLimit != null && responseLimit !in 1L..MAX_RESPONSE_BYTES) {
            return function.error("@ResponseLimit 必须在 1..$MAX_RESPONSE_BYTES 字节之间")
        }
        val retry = function.annotation(RETRY)?.let(::parseRetry)
        if (retry != null && !retry.isValid()) {
            return function.error("@Retry 参数无效：次数 1..6，延迟递增，倍数不小于 1，抖动范围 0..1")
        }
        val cache = function.annotation(CACHE)?.let(::parseCache)
        if (cache != null && (httpMethod != "GET" || !cache.isValid())) {
            return function.error("@Cache 参数无效或用于了非 GET 接口")
        }
        val priority = function.annotation(PRIORITY)?.argument("value")?.toString()?.substringAfterLast('.')
        val tags = function.annotation(TAGS)?.argument("value").asStringList()
        val invalidationTags = function.annotation(INVALIDATE_CACHE)?.argument("tags").asStringList()
        if ((tags + invalidationTags + (cache?.tags ?: emptyList())).any { !NETWORK_TAG.matches(it) }) {
            return function.error("网络标签只能使用字母、数字和 -_.:/，长度 1..64")
        }
        val redirects = function.annotation(REDIRECTS)?.let(::parseRedirects)
        if (redirects != null && redirects.maxRedirects !in 0..10) {
            return function.error("@Redirects maxRedirects 必须在 0..10 之间")
        }

        val fixedHeaders = function.annotation(HEADERS)?.argument("value").asStringList().mapNotNull { header ->
            val separator = header.indexOf(':')
            if (separator <= 0) {
                logger.error("@Headers 必须使用 Name: Value 格式", function)
                return@mapNotNull null
            }
            val name = header.substring(0, separator).trim()
            val value = header.substring(separator + 1).trim()
            if (!HEADER_NAME.matches(name) || value.any { it == '\r' || it == '\n' }) {
                logger.error("@Headers 必须使用 Name: Value 格式", function)
                null
            } else {
                name to value
            }
        }
        return MethodContract(
            name = function.simpleName.asString(),
            isSuspend = Modifier.SUSPEND in function.modifiers,
            returnType = returnType.render(),
            responseType = returnContract.responseType.render(),
            returnKind = returnContract.kind,
            httpMethod = httpMethod,
            path = path,
            parameters = parameters,
            fixedHeaders = fixedHeaders,
            timeoutSeconds = timeoutSeconds,
            responseLimit = responseLimit,
            retry = retry,
            cache = cache,
            bodyMode = bodyMode,
            dynamicUrlParameter = dynamicUrl?.name,
            streaming = streaming,
            priority = priority,
            tags = tags,
            invalidationTags = invalidationTags,
            redirects = redirects,
        )
    }

    private fun parseParameter(parameter: KSValueParameter): ParameterContract? {
        val annotations = PARAMETER_ANNOTATIONS.mapNotNull { (name, kind) ->
            parameter.annotation(name)?.let { kind to it }
        }
        if (annotations.size != 1) {
            val names = parameter.annotations.joinToString { annotation ->
                val declaration = annotation.annotationType.resolve().declaration
                declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
            }
            return parameter.error("请求参数 ${parameter.name?.asString().orEmpty()} 必须且只能声明一个网络参数注解，当前：$names")
        }
        val (kind, annotation) = annotations.single()
        val type = parameter.type.resolve()
        if (kind in setOf(ParameterKind.PATH, ParameterKind.URL, ParameterKind.IDEMPOTENCY_KEY, ParameterKind.REQUEST_ID) &&
            type.nullability == Nullability.NULLABLE
        ) {
            return parameter.error("@Path、@Url、@IdempotencyKey 和 @RequestId 参数不能为可空类型")
        }
        if (kind in setOf(ParameterKind.QUERY_MAP, ParameterKind.HEADER_MAP, ParameterKind.FIELD_MAP, ParameterKind.PART_MAP) &&
            (type.nullability == Nullability.NULLABLE || !type.isStringMap())
        ) {
            return parameter.error("Map 参数只支持非空 Map<String, String?> 或 Map<String, String>")
        }
        if (kind == ParameterKind.URL && type.declaration.qualifiedName?.asString() != "kotlin.String") {
            return parameter.error("@Url 只支持非空 String")
        }
        if (kind == ParameterKind.PART && type.declaration.qualifiedName?.asString() !in MULTIPART_TYPES) {
            return parameter.error("@Part 只支持 String、ByteArray 或 NetworkMultipartPart")
        }
        val wireName = when (kind) {
            ParameterKind.BODY, ParameterKind.URL, ParameterKind.QUERY_MAP, ParameterKind.HEADER_MAP,
            ParameterKind.FIELD_MAP, ParameterKind.PART_MAP -> ""
            ParameterKind.IDEMPOTENCY_KEY -> "Idempotency-Key"
            ParameterKind.REQUEST_ID -> "X-Request-ID"
            else -> annotation.argument("value") as? String
                ?: return parameter.error("参数注解缺少名称")
        }
        if (kind == ParameterKind.PATH && !ROUTE_NAME.matches(wireName)) {
            return parameter.error("@Path 名称只能使用字母、数字和下划线，且必须以字母开头")
        }
        if (kind in setOf(ParameterKind.QUERY, ParameterKind.FIELD, ParameterKind.PART) &&
            (wireName.isEmpty() || wireName.any { it.code < 32 })
        ) {
            return parameter.error("参数名称不能为空或包含控制字符")
        }
        if (kind == ParameterKind.HEADER && !HEADER_NAME.matches(wireName)) {
            return parameter.error("@Header 名称不是有效的 HTTP Header 名称")
        }
        return ParameterContract(
            name = parameter.name?.asString() ?: return parameter.error("请求参数缺少名称"),
            type = type.render(),
            nullable = type.nullability == Nullability.NULLABLE,
            kind = kind,
            wireName = wireName,
            fileName = annotation.argument("fileName") as? String ?: "",
            contentType = annotation.argument("contentType") as? String ?: "",
        )
    }

    private fun parseRetry(annotation: KSAnnotation): RetryContract = RetryContract(
        maxAttempts = annotation.argument("maxAttempts") as Int,
        initialDelayMillis = annotation.argument("initialDelayMillis") as Long,
        maxDelayMillis = annotation.argument("maxDelayMillis") as Long,
        multiplier = annotation.argument("multiplier") as Double,
        jitterRatio = annotation.argument("jitterRatio") as Double,
        retryUnsafeMethods = annotation.argument("retryUnsafeMethods") as Boolean,
    )

    private fun parseCache(annotation: KSAnnotation): CacheContract = CacheContract(
        maxAgeSeconds = annotation.argument("maxAgeSeconds") as Int,
        staleIfErrorSeconds = annotation.argument("staleIfErrorSeconds") as Int,
        mode = annotation.argument("mode").toString().substringAfterLast('.'),
        staleWhileRevalidateSeconds = annotation.argument("staleWhileRevalidateSeconds") as Int,
        tags = annotation.argument("tags").asStringList(),
    )

    private fun parseRedirects(annotation: KSAnnotation): RedirectContract = RedirectContract(
        enabled = annotation.argument("enabled") as Boolean,
        maxRedirects = annotation.argument("maxRedirects") as Int,
        allowCrossOrigin = annotation.argument("allowCrossOrigin") as Boolean,
    )

    private fun <T> KSNode.error(message: String): T? {
        logger.error(message, this)
        return null
    }
}
