# 上传、下载与进度

## 上传

小文件可以直接使用 Multipart `ByteArray`：

```kotlin
@Multipart
@POST("/v1/avatar")
fun uploadAvatar(
    @Part(value = "file", fileName = "avatar.jpg", contentType = "image/jpeg") bytes: ByteArray,
    @IdempotencyKey key: String,
): NetworkCall<AvatarDto>
```

```kotlin
api.uploadAvatar(bytes, uploadId)
    .timeout(120)
    .progress { progress -> render(progress) }
    .await()
```

Android 按块写入 OkHttp Sink 并报告进度；iOS 在创建请求任务时报告已提交正文的上传进度。对于很大的文件，
应实现直接读取文件/流的专用 Engine，避免整个文件常驻 `ByteArray`。

## 下载

```kotlin
@Streaming
@GET("/v1/files/{id}")
fun download(@Path("id") id: String): NetworkCall<ByteArray>
```

```kotlin
val result = api.download(id)
    .responseLimit(20L * 1024 * 1024)
    .progress { progress -> render(progress) }
    .await()
```

当前 `@Streaming` 仍返回内存 `ByteArray`，只是跳过 JSON 解码并提供字节进度，不等于无限大文件落盘流。

## 进度模型

```kotlin
data class NetworkTransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val direction: NetworkTransferDirection,
)
```

当服务端没有 Content-Length 时 `totalBytes` 为 null，此时 UI 应显示不确定进度，而不是除以零。

```kotlin
fun render(progress: NetworkTransferProgress) {
    val ratio = progress.totalBytes
        ?.takeIf { it > 0L }
        ?.let { progress.bytesTransferred.toDouble() / it }
    if (ratio == null) showIndeterminate() else showPercent(ratio)
}
```

## 取消与重试

- 取消收集/协程会取消平台任务。
- 进度回调异常不会中断传输。
- 上传重试必须提供幂等键，并确认服务端能去重已接收的请求。
- 下载重试会重新开始当前请求；当前 Core 没有断点续传协议。

## Base URL 与 CDN

服务端返回签名 CDN 地址时使用 `@Url`：

```kotlin
@Streaming
@GET
fun downloadSigned(@Url httpsUrl: String): NetworkCall<ByteArray>
```

动态 URL 只接受 HTTPS 且禁止用户信息和 fragment。对于固定上传/下载域名，更推荐命名 Client：

```kotlin
@NetworkService(client = "upload")
interface UploadApi
```

这样 TLS、超时、Header 与限流策略可以和主 API 分开配置。

## HarmonyOS

当前 HAR Bridge 只支持 JSON 文本请求。二进制、流式和真实进度需要宿主实现专用 Harmony `NetworkEngine`；
文档不会把 Bridge 构建成功等同于这些能力已经可用。
