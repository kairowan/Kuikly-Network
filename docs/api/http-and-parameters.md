# HTTP 与 URL 参数

## 固定相对路径

```kotlin
@GET("/v1/projects/{projectId}/issues/{issueId}")
fun issue(
    @Path("projectId") projectId: String,
    @Path("issueId") issueId: Long,
    @Query("expand") expand: String? = null,
): NetworkCall<IssueDto>
```

固定路径必须：

- 以 `/` 开头。
- 不包含 `?`、`#`、`..`、反斜杠或控制字符。
- `{name}` 只能使用字母开头的字母/数字/下划线名称。
- 每个占位符都有且只有一个同名 `@Path`。

`@Path` 和 `@Query` 的值会由 URL Builder 编码，不要先手工拼 `%2F` 等转义文本。

## HTTP 方法语义

```kotlin
@GET("/v1/items") fun list(): NetworkCall<List<ItemDto>>
@POST("/v1/items") fun create(@Body body: CreateItem): NetworkCall<ItemDto>
@PUT("/v1/items/{id}") fun replace(@Path("id") id: String, @Body body: ItemDto): NetworkCall<ItemDto>
@PATCH("/v1/items/{id}") fun update(@Path("id") id: String, @Body body: PatchItem): NetworkCall<ItemDto>
@DELETE("/v1/items/{id}") fun delete(@Path("id") id: String): NetworkCall<Unit>
@HEAD("/v1/items/{id}") fun exists(@Path("id") id: String): NetworkCall<Unit>
@OPTIONS("/v1/items") fun options(): NetworkCall<Unit>
```

KSP 禁止 `GET` 和 `HEAD` 携带 `@Body`、Field 或 Part。非幂等写请求默认不会因为普通网络错误自动重试；
需要重试时应提供 `@IdempotencyKey`，或明确启用 `retryUnsafeMethods` 并确认服务端语义安全。

## Query 与 QueryMap

```kotlin
@GET("/v1/search")
fun search(
    @Query("keyword") keyword: String,
    @Query("page") page: Int = 1,
    @Query("cursor") cursor: String? = null,
    @QueryMap filters: Map<String, String?> = emptyMap(),
): NetworkCall<SearchResult>
```

- 单个可空值为 null 时不发送。
- Map 中值为 null 的条目不发送。
- 同名参数可以重复，内部使用 Pair 列表保留顺序。
- Query 名称不能包含控制字符。

## Header 与 HeaderMap

```kotlin
@GET("/v1/profile")
@Headers(
    "Accept: application/json",
    "X-Client: kuikly",
)
fun profile(
    @Header("If-None-Match") etag: String?,
    @HeaderMap tracing: Map<String, String?>,
): NetworkCall<ProfileDto>
```

固定 Header 用 `@Headers("Name: Value")`，动态 Header 用方法参数。Header 名称和内容会在编译期/运行时
校验，拒绝换行符，防止 Header 注入。

## RequestId 与 IdempotencyKey

```kotlin
@POST("/v1/orders")
fun createOrder(
    @Body request: CreateOrder,
    @RequestId requestId: String,
    @IdempotencyKey idempotencyKey: String,
): NetworkCall<OrderDto>
```

- `@RequestId` 对应 `X-Request-ID`，用于日志、Inspector 和服务端链路追踪。
- `@IdempotencyKey` 对应 `Idempotency-Key`，让写请求可以在服务端去重。
- 两者都不进入 JSON Body。
- 调用链也可用 `.idempotencyKey(value)` 动态添加，长度必须在 1..191。

## 动态完整 URL

```kotlin
@GET
fun downloadFromCdn(@Url url: String): NetworkCall<FileMeta>
```

使用 `@Url` 时 HTTP 注解路径必须为空，且不能同时使用 `@Path`。动态地址只允许：

- `https://`。
- authority 非空且不包含用户信息（`user@host`）。
- 不包含 fragment 或控制字符。
- 总长度不超过 8192。

这适合服务端返回的签名 CDN 地址。普通接口应继续使用相对路径，以免绕过统一 Base URL 与安全策略。

## 手写 URL Builder

不使用 KSP 时可复用同一套安全拼接：

```kotlin
val path = networkUrl("/v1/search") {
    segment(category)
    query("keyword", keyword)
    query("cursor", cursor)
}
```

Builder 会编码 path segment 与 query 值；不要使用字符串插值手动构造用户输入 URL。
