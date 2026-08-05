# Converter 与响应外壳

## 自定义 Converter

`NetworkConverter` 负责 Kotlin Serialization Strategy 与文本正文之间的转换：

```kotlin
class CompanyJsonConverter(
    private val json: Json,
) : NetworkConverter {
    override fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
    ): String = json.encodeToString(serializer, value)

    override fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        value: String,
    ): T = json.decodeFromString(deserializer, value)
}
```

```kotlin
val client = createNetworkClient(baseUrl) {
    converter(
        CompanyJsonConverter(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                coerceInputValues = true
            },
        ),
    )
}
```

Converter 是 Client 级策略。单个特殊接口可以使用 `NetworkEndpoint` 自己的 Encoder/Decoder，不必影响全局。

## 统一响应外壳

服务端常返回：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "id": "42" }
}
```

用 `NetworkResponseAdapter` 在 DTO 解码前统一拆解：

```kotlin
class CompanyEnvelopeAdapter(
    private val json: Json = Json,
) : NetworkResponseAdapter {
    override fun adapt(response: NetworkRawResponse): NetworkPayload {
        val root = json.parseToJsonElement(response.body).jsonObject
        val code = root["code"]?.jsonPrimitive?.intOrNull
        if (code == 0) {
            val data = root["data"] ?: JsonNull
            return NetworkPayload.Data(data.toString())
        }
        return NetworkPayload.Failure(
            NetworkFailure(
                code = "business_${code ?: "unknown"}",
                category = NetworkFailureCategory.HTTP,
                message = root["message"]?.jsonPrimitive?.content.orEmpty(),
                statusCode = response.statusCode,
            ),
        )
    }
}
```

```kotlin
val client = createNetworkClient(baseUrl) {
    responseAdapter(CompanyEnvelopeAdapter())
}
```

业务 Service 仍直接返回 `UserDto`，不需要每个接口重复声明 `Envelope<UserDto>` 或判断 code。

!!! note

    Adapter 应保留 HTTP 状态、Request ID 等可观测信息，并对空 Body/204 定义清晰规则。解析异常会进入结构化
    serialization failure，而不是悄悄当成功。

## 自定义 HTTP 错误映射

```kotlin
val mapper = NetworkErrorMapper { response ->
    when (response.statusCode) {
        401 -> NetworkFailure(
            code = "session_expired",
            category = NetworkFailureCategory.HTTP,
            statusCode = 401,
        )
        429 -> NetworkFailure(
            code = "server_rate_limited",
            category = NetworkFailureCategory.HTTP,
            statusCode = 429,
            retryable = true,
        )
        else -> NetworkFailure(
            code = "http_${response.statusCode}",
            category = NetworkFailureCategory.HTTP,
            statusCode = response.statusCode,
        )
    }
}

val client = createNetworkClient(baseUrl) {
    errorMapper(mapper)
}
```

错误 Mapper 适合 HTTP 层规则；JSON 业务外壳优先使用 Response Adapter，避免 Mapper 和 DTO Converter 重复解析。

## 单 Endpoint 编解码

```kotlin
val endpoint = NetworkEndpoint<Unit, String>(
    method = NetworkMethod.GET,
    path = { "/v1/plain-text" },
    requestEncoder = NetworkCodecs.unitEncoder,
    responseDecoder = NetworkCodecs.stringDecoder,
)

val call = client.call(endpoint, Unit)
```

这适合少量纯文本/自定义协议。大批稳定接口仍推荐 KSP Contract。
