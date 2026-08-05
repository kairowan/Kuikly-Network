# 注解总览

注解只描述 Contract，实际实现由 KSP 编译期生成。生成代码最终仍调用公开的 `NetworkClient`，没有运行时代理。

## Service

| 注解 | 位置 | 作用 |
| --- | --- | --- |
| `@NetworkService` | 顶层 interface | 标记需要生成实现的接口，并选择序列化与命名 Client |

```kotlin
@NetworkService(
    serialization = NetworkSerialization.KOTLINX,
    client = "default",
)
interface UserApi
```

- 只支持非泛型顶层接口。
- 接口至少为 `internal`，不能是 private/protected。
- `client` 默认是 `default`，对应 `NetworkClients` 注册表。
- `KOTLINX` 跨端可用；`GSON` 依赖 JVM 反射，只能用于 Android 源集。

## HTTP 方法

| 注解 | 是否允许请求体 | 常见用途 |
| --- | :---: | --- |
| `@GET` | 否 | 查询资源 |
| `@POST` | 是 | 创建、执行命令 |
| `@PUT` | 是 | 完整替换资源 |
| `@PATCH` | 是 | 局部更新 |
| `@DELETE` | 可选 | 删除资源 |
| `@HEAD` | 否 | 只读取响应头 |
| `@OPTIONS` | 可选 | 查询服务能力 |

每个方法必须且只能有一个 HTTP 注解。详细路径规则见 [HTTP 与 URL 参数](http-and-parameters.md)。

## 参数注解

| 注解 | 传输位置 | 支持类型/规则 |
| --- | --- | --- |
| `@Path` | URL 路径模板 | 非空值，名称与 `{name}` 一一对应 |
| `@Url` | 完整 URL | 非空 `String`，仅安全 HTTPS |
| `@Query` | 查询参数 | 任意可转字符串类型；null 时省略 |
| `@QueryMap` | 查询参数集合 | `Map<String, String?>` / `Map<String, String>` |
| `@Header` | 动态 Header | 名称必须是合法 HTTP Header 名 |
| `@HeaderMap` | Header 集合 | `Map<String, String?>` / `Map<String, String>` |
| `@Body` | JSON 正文 | 每个方法最多一个 |
| `@Field` | Form 字段 | 配合 `@FormUrlEncoded` |
| `@FieldMap` | Form 字段集合 | 配合 `@FormUrlEncoded` |
| `@Part` | Multipart Part | `String`、`ByteArray`、`NetworkMultipartPart` |
| `@PartMap` | Multipart 文本集合 | 配合 `@Multipart` |
| `@IdempotencyKey` | `Idempotency-Key` | 非空值，用于写请求安全重试 |
| `@RequestId` | `X-Request-ID` | 非空值，用于链路关联 |

每个方法参数必须且只能声明一个网络参数注解。

## Body 与传输注解

| 注解 | 作用 |
| --- | --- |
| `@FormUrlEncoded` | 使用 `application/x-www-form-urlencoded` |
| `@Multipart` | 使用 `multipart/form-data` |
| `@Streaming` | 返回原始 `ByteArray` 并启用下载进度 |

详细示例见 [Body、表单与文件](body-and-files.md)。

## 请求策略注解

| 注解 | 作用 |
| --- | --- |
| `@Headers` | 添加固定 Header，格式为 `Name: Value` |
| `@Timeout` | 单接口超时，1..300 秒 |
| `@Retry` | 有限指数退避重试 |
| `@ResponseLimit` | 最大响应正文，最多 20 MiB |
| `@Cache` | GET 缓存模式、有效期与标签 |
| `@Priority` | 调度优先级 |
| `@Tags` | 业务/限流/调试标签，不进入 URL 或 Header |
| `@Redirects` | 重定向开关、次数与跨域策略 |
| `@InvalidateCache` | 写请求成功后精确失效缓存标签 |

详细参数、优先级和覆盖规则见 [请求策略注解](policies.md)。

## 注解、默认值与调用链优先级

同一个策略存在多个配置入口时：

```text
NetworkClient 默认值 < 接口注解 < NetworkCall 调用链
```

例如 Client 默认超时 30 秒，接口 `@Timeout(15)`，调用处 `.timeout(5)`，最终使用 5 秒。调用链返回的是新
`NetworkCall`，不会改变 Service 或 Client 的其他请求。

## 编译期错误是设计的一部分

以下问题会直接让 KSP 编译失败：

- 路径占位符和 `@Path` 不一致。
- `GET`/`HEAD` 声明请求体。
- `@FormUrlEncoded` 与 `@Multipart` 同时存在。
- `@Streaming` 返回类型不是 `ByteArray`。
- `@Url` 与固定路径或 `@Path` 混用。
- Header、标签、Client 名称或策略范围非法。

把错误留在编译期，比等到用户触发接口后再报运行时错误更容易维护。
