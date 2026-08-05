# 安装与 KSP

## 要求

- Kotlin `2.1.21`
- Android minSdk `24`、JDK `17`
- iOS arm64 / iOS Simulator arm64
- 使用声明式接口时启用 KSP `2.1.21-2.0.1`
- HarmonyOS 使用仓库内 KBA 子构建和 DevEco/OpenHarmony SDK

## 取得依赖

稳定版本从 JitPack 获取。消费工程配置仓库：

```kotlin title="settings.gradle.kts"
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.kairowan.Kuikly-Network") }
        }
    }
}
```

JitPack 的多模块 Group 是 `com.github.kairowan.Kuikly-Network`，版本使用 Git Tag `v0.1.0`。

## Android / KMP 共享代码使用 JitPack

```kotlin title="shared/build.gradle.kts"
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

val networkVersion = "v0.1.0"

kotlin {
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(platform("com.github.kairowan.Kuikly-Network:network-bom:$networkVersion"))
            implementation("com.github.kairowan.Kuikly-Network:network-core:$networkVersion")
        }

        commonTest.dependencies {
            implementation("com.github.kairowan.Kuikly-Network:network-testing:$networkVersion")
        }
    }
}

dependencies {
    add("kspAndroid", "com.github.kairowan.Kuikly-Network:network-ksp:$networkVersion")
}
```

上面的 JitPack 产物用于 Android 目标；业务接口和 Repository 仍然可以放在 `commonMain`。

## iOS 使用 Release Maven 包

从 [GitHub Release](https://github.com/kairowan/Kuikly-Network/releases/tag/v0.1.0) 下载
`kuikly-network-maven-0.1.0.zip`，解压到项目的 `repo` 目录：

```kotlin title="settings.gradle.kts"
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri(rootDir.resolve("repo/maven")) }
    }
}
```

完整 KMP 坐标使用发布物原始 Group：

```kotlin title="shared/build.gradle.kts"
val networkVersion = "0.1.0"

commonMain.dependencies {
    implementation(platform("com.catchzoon.network:network-bom:$networkVersion"))
    implementation("com.catchzoon.network:network-core:$networkVersion")
}

dependencies {
    add("kspAndroid", "com.catchzoon.network:network-ksp:$networkVersion")
    add("kspIosArm64", "com.catchzoon.network:network-ksp:$networkVersion")
    add("kspIosSimulatorArm64", "com.catchzoon.network:network-ksp:$networkVersion")
}
```

Release Maven 包包含 Android、KMP metadata、iOS arm64、iOS Simulator arm64、KSP、BOM 和所有可选模块。

!!! note "KSP 是编译期依赖"

    `network-ksp` 不需要加入 `commonMain`。每个会编译 `@NetworkService` 接口的目标都要配置对应的 KSP
    configuration，否则该目标看不到生成工厂。

## 可选模块

```kotlin
commonMain.dependencies {
    implementation("com.github.kairowan.Kuikly-Network:network-kuikly:$networkVersion")
    implementation("com.github.kairowan.Kuikly-Network:network-inspector:$networkVersion")
    implementation("com.github.kairowan.Kuikly-Network:network-koin:$networkVersion")
    implementation("com.github.kairowan.Kuikly-Network:network-realtime:$networkVersion")
}
```

只加实际用到的模块。比如不用 Koin，就没有理由把 Koin 带进公共依赖图。

!!! info "JitPack 与 Apple 产物"

    JitPack 在 Linux 上构建 KMP 根元数据、Android AAR、KSP 和 BOM。GitHub Release 中的 Maven
    压缩包由 macOS 构建，额外包含 iOS arm64 与 iOS Simulator arm64 KLib。不要在同一配置中混用
    `com.github.kairowan.Kuikly-Network` 和 `com.catchzoon.network` 两组坐标。

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
