package com.catchzoon.network.annotation

/** API 接口使用的序列化实现。GSON 仅允许 Android 源集使用。 */
public enum class NetworkSerialization { KOTLINX, GSON }

/** 标记需要由 KSP 生成实现类的网络接口。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class NetworkService(
    val serialization: NetworkSerialization = NetworkSerialization.KOTLINX,
    /** KSP 无参工厂从 NetworkClients 取得的客户端名称。 */
    val client: String = "default",
)

/** 声明 GET 接口。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class GET(val value: String = "")

/** 声明 POST 接口。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class POST(val value: String = "")

/** 声明 PUT 接口。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class PUT(val value: String = "")

/** 声明 PATCH 接口。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class PATCH(val value: String = "")

/** 声明 DELETE 接口。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class DELETE(val value: String = "")

/** 声明 HEAD 接口。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class HEAD(val value: String = "")

/** 声明 OPTIONS 接口。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OPTIONS(val value: String = "")

/** 声明固定请求头，格式为 `Name: Value`。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Headers(vararg val value: String)

/** 把参数绑定到 URL 的 `{name}` 占位符。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Path(val value: String)

/** 使用运行时提供的完整 HTTPS URL。每个接口最多声明一个。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Url

/** 把参数编码为查询参数；可空值为 null 时不会发送。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Query(val value: String)

/** 批量添加查询参数；值为 null 的条目不会发送。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class QueryMap

/** 把参数作为动态请求头。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Header(val value: String)

/** 批量添加动态请求头；值为 null 的条目不会发送。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class HeaderMap

/** 把参数作为请求正文，每个方法最多声明一个。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Body

/** 把参数编码为 application/x-www-form-urlencoded 字段。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Field(val value: String)

/** 批量添加表单字段。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class FieldMap

/** 把参数编码为 multipart 字段。ByteArray 字段可以同时声明文件名和类型。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Part(
    val value: String,
    val fileName: String = "",
    val contentType: String = "application/octet-stream",
)

/** 批量添加 multipart 文本字段。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class PartMap

/** 把参数作为 Idempotency-Key，不会写入请求正文。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class IdempotencyKey

/** 把参数作为 X-Request-ID，便于客户端日志和服务端链路追踪关联。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class RequestId

/** 当前方法使用 application/x-www-form-urlencoded。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class FormUrlEncoded

/** 当前方法使用 multipart/form-data。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Multipart

/** 当前方法返回原始 ByteArray，并启用下载进度。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Streaming

/** 声明请求调度优先级。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Priority(val value: com.catchzoon.network.core.NetworkPriority)

/** 声明不进入 URL 或 Header 的业务标签。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Tags(vararg val value: String)

/** 声明重定向行为。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Redirects(
    val enabled: Boolean = true,
    val maxRedirects: Int = 5,
    val allowCrossOrigin: Boolean = false,
)

/** 写请求成功后精确失效指定缓存标签。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class InvalidateCache(vararg val tags: String)

/** 声明当前接口超时秒数。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Timeout(val seconds: Int)

/** 声明当前接口有限重试策略。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Retry(
    val maxAttempts: Int,
    val initialDelayMillis: Long = 300L,
    val maxDelayMillis: Long = 5_000L,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.2,
    val retryUnsafeMethods: Boolean = false,
)

/** 声明当前接口最大响应正文大小。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ResponseLimit(val bytes: Long)

/** 声明 GET 响应缓存和在线失败时允许使用的过期缓存时长。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Cache(
    val maxAgeSeconds: Int,
    val staleIfErrorSeconds: Int = 0,
    val mode: com.catchzoon.network.core.NetworkCacheMode = com.catchzoon.network.core.NetworkCacheMode.CACHE_FIRST,
    val staleWhileRevalidateSeconds: Int = 0,
    val tags: Array<String> = [],
)
