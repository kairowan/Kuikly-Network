# 安装与 KSP

## 要求

- Kotlin `2.1.21`
- Android minSdk `24`、JDK `17`
- iOS arm64 / iOS Simulator arm64
- 使用声明式接口时启用 KSP `2.1.21-2.0.1`
- HarmonyOS 使用仓库内 KBA 子构建和 DevEco/OpenHarmony SDK

## 取得依赖

当前仓库默认版本是 `0.1.0-SNAPSHOT`，尚未声明已经发布到 Maven Central。开发阶段先发布到
`mavenLocal()`：

```bash
./gradlew publishToMavenLocal -PnetworkVersion=0.1.0
```

消费工程配置仓库：

```kotlin title="settings.gradle.kts"
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
```

正式发布到内部或公共 Maven 后，将 `mavenLocal()` 换成对应仓库即可，坐标保持不变。

## KMP 依赖

```kotlin title="shared/build.gradle.kts"
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(platform("com.catchzoon.network:network-bom:0.1.0"))
            implementation("com.catchzoon.network:network-core")
        }

        commonTest.dependencies {
            implementation("com.catchzoon.network:network-testing:0.1.0")
        }
    }
}

dependencies {
    add("kspAndroid", "com.catchzoon.network:network-ksp:0.1.0")
    add("kspIosArm64", "com.catchzoon.network:network-ksp:0.1.0")
    add("kspIosSimulatorArm64", "com.catchzoon.network:network-ksp:0.1.0")
}
```

!!! note "KSP 是编译期依赖"

    `network-ksp` 不需要加入 `commonMain`。每个会编译 `@NetworkService` 接口的目标都要配置对应的 KSP
    configuration，否则该目标看不到生成工厂。

## 可选模块

```kotlin
commonMain.dependencies {
    implementation("com.catchzoon.network:network-kuikly")
    implementation("com.catchzoon.network:network-inspector")
    implementation("com.catchzoon.network:network-koin")
    implementation("com.catchzoon.network:network-realtime")
}
```

只加实际用到的模块。比如不用 Koin，就没有理由把 Koin 带进公共依赖图。

## 验证代码生成

先声明一个最小接口：

```kotlin
@NetworkService
interface HealthApi {
    @GET("/health")
    fun health(): NetworkCall<HealthDto>
}
```

执行目标编译：

```bash
./gradlew :shared:compileDebugKotlinAndroid
./gradlew :shared:compileKotlinIosSimulatorArm64
```

KSP 会在每个平台生成两个工厂：

```kotlin
fun NetworkClient.createHealthApi(): HealthApi
fun createHealthApi(clientName: String = "default"): HealthApi
```

前者适合测试和显式依赖注入，后者从应用级 `NetworkClients` 注册表读取客户端。

## HarmonyOS HAR

```bash
DEVECO_STUDIO_HOME=/Applications/DevEco-Studio.app \
  ./scripts/build_harmony.sh har
```

产物位于：

```text
network-ohos/build/default/outputs/default/network_ohos.har
```

完整接入见 [HarmonyOS / HAR](../platforms/harmony.md)。
