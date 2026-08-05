# 测试与校验

`network-testing` 提供 `ScriptedNetworkEngine`，可以在不访问真实网络的情况下按顺序返回响应、异常、离线、超时和延迟场景。

## 添加测试依赖

```kotlin
commonTest.dependencies {
    implementation(kotlin("test"))
    implementation("com.catchzoon.network:network-testing:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:<coroutines-version>")
}
```

## 最小测试

```kotlin
class UserRepositoryTest {
    @Test
    fun loadsUser() = runTest {
        val engine = ScriptedNetworkEngine(
            scenarios = listOf(
                respond(
                    statusCode = 200,
                    body = """{"id":1,"name":"Ada"}""",
                ),
            ),
        )
        val client = engine.client()
        val api = createUserApi(client)

        val result = api.user(1).await()

        assertEquals("Ada", result.getOrThrow().name)
        assertEquals("/users/1", engine.recordedRequests().single().relativePath)
        assertEquals(0, engine.remainingScenarios())
    }
}
```

测试中显式传入客户端，避免依赖上一个测试残留的全局注册表。

## 可编排场景

```kotlin
val engine = ScriptedNetworkEngine(
    listOf(
        fail("connection_reset"),
        offline(),
        timeout(),
        delayed(
            delayMillis = 500,
            next = NetworkScenario.Handle { request ->
                NetworkRawResponse(
                    body = request.body,
                    statusCode = 200,
                )
            },
        ),
    ),
)
```

场景按加入顺序消费。测试结束时断言 `remainingScenarios() == 0`，可以发现某个分支没有真正发出请求。

## 应优先覆盖什么

1. URL、Path、Query、Header 和 Body 是否按接口声明生成；
2. 401 刷新后是否只重放一次请求；
3. POST 在没有幂等键时是否不会自动重试；
4. 缓存命中、过期与 `staleIfError` 是否符合业务预期；
5. 取消调用者协程后，引擎是否停止对应请求；
6. 自定义拦截器、转换器或错误映射器的边界行为。

## 仓库级验证

```bash
./gradlew test
./gradlew :androidApp:assembleDebug
./gradlew :shared:generateDummyFramework
DEVECO_STUDIO_HOME=/Applications/DevEco-Studio.app ./scripts/build_harmony.sh har
DEVECO_STUDIO_HOME=/Applications/DevEco-Studio.app ./scripts/build_harmony.sh hap
```

平台环境不齐全时至少执行公共单元测试；发布前再补齐 Android、iOS 和 HarmonyOS 产物验证。
