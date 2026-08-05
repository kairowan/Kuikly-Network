import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.kotlin

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.0.21-KBA-010"
    id("com.google.devtools.ksp")
    id("com.tencent.kuiklybase.knoi.plugin")
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    ohosArm64 {
        binaries.sharedLib("shared") {
            freeCompilerArgs += "-Xadd-light-debug=enable"
            linkerOpts += "--build-id=sha1"
            if (buildType == org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE) {
                freeCompilerArgs += "-Xoverride-konan-properties=clangOptFlags.ohos_arm64=-Os -ffunction-sections;clangDebugFlags.ohos_arm64=-Os -ffunction-sections"
                linkerOpts += listOf("--pack-dyn-relocs=relr", "--gc-sections")
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDirs(
                "../../shared/src/commonMain/kotlin",
                "../../network-core/src/commonMain/kotlin",
                "../../network-kuikly/src/commonMain/kotlin",
            )
            dependencies {
                implementation(project(":core"))
                implementation(project(":core-annotations"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:2.0.21-coroutines-KBA-001")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1-KBA-003")
            }
        }
    }
}

dependencies {
    add("kspOhosArm64", project(":core-ksp"))
    add("kspOhosArm64", project(":kuikly-network-ksp"))
}

ksp {
    arg("pageName", "network_sample")
    arg("catchException", "false")
}
