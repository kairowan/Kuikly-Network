# Body、表单与文件

## JSON Body

```kotlin
@Serializable
data class CreateArticle(val title: String, val content: String)

@POST("/v1/articles")
fun create(@Body request: CreateArticle): NetworkCall<ArticleDto>
```

每个方法最多一个 `@Body`。Kotlin Serialization 接口会使用 DTO 的 serializer；Android-only 接口可以在
`@NetworkService(serialization = NetworkSerialization.GSON)` 中选择 Gson。

## Form

```kotlin
@FormUrlEncoded
@POST("/oauth/token")
fun token(
    @Field("grant_type") grantType: String,
    @Field("code") code: String,
    @FieldMap extra: Map<String, String?> = emptyMap(),
): NetworkCall<TokenDto>
```

规则：

- `@FormUrlEncoded` 至少包含一个 `@Field` 或 `@FieldMap`。
- Form 方法不能混用 `@Body`、`@Part` 或 `@PartMap`。
- null 字段会省略。
- 字段名称和值使用 `application/x-www-form-urlencoded` 编码。

## Multipart 文本和文件

```kotlin
@Multipart
@POST("/v1/files")
fun upload(
    @Part("title") title: String,
    @Part(
        value = "file",
        fileName = "avatar.jpg",
        contentType = "image/jpeg",
    ) bytes: ByteArray,
    @PartMap metadata: Map<String, String?> = emptyMap(),
    @IdempotencyKey idempotencyKey: String,
): NetworkCall<UploadResult>
```

`@Part` 支持：

- `String`：普通文本字段。
- `ByteArray`：内存中的文件数据。
- `NetworkMultipartPart`：调用方需要动态文件名、Content-Type 或已经构造 Part 时使用。

`@PartMap` 只处理文本 Map。可空 Part 为 null 时省略。

## 动态 Part

```kotlin
val part = NetworkMultipartPart.file(
    name = "file",
    bytes = content,
    fileName = selectedName,
    contentType = selectedMime,
)
```

也可以使用重载工厂：

```kotlin
networkMultipartPart("title", "avatar")
networkMultipartPart("file", bytes, "avatar.jpg", "image/jpeg")
```

Multipart Builder 会校验名称、文件名、Content-Type 和换行符，并生成随机 boundary。二进制数据不会先转成
JSON 字符串。

## 原始 ByteArray 下载

```kotlin
@Streaming
@GET("/v1/files/{id}/content")
fun download(@Path("id") id: String): NetworkCall<ByteArray>
```

`@Streaming` 的返回类型必须是 `ByteArray`。Android/iOS Engine 会执行响应大小检查并发送下载进度；当前
Harmony Bridge 只支持 JSON 文本，二进制和流式下载需要专用 Harmony Engine。

## 上传/下载进度

```kotlin
api.upload(title, bytes, metadata, key)
    .progress { progress ->
        val total = progress.totalBytes
        val percent = total?.takeIf { it > 0L }?.let {
            progress.bytesTransferred * 100 / it
        }
        renderProgress(progress.direction, percent)
    }
    .await()
```

进度回调是旁路能力：回调自身抛出的普通异常不会中断传输。UI 更新仍应切换到平台要求的主线程/主 Actor。

## 响应大小上限

```kotlin
@ResponseLimit(8 * 1024 * 1024)
@Streaming
@GET("/v1/archive")
fun archive(): NetworkCall<ByteArray>
```

注解范围是 1B..20MiB。调用链 `.responseLimit(bytes)` 可以覆盖注解。大文件不应无限提高内存上限，应使用专用
流式 Engine 或落盘 API。
