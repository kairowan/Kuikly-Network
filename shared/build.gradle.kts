import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
    id("com.tencent.kuikly-open.kuikly")
    id("org.jetbrains.kotlin.native.cocoapods")
}

kotlin {
    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(17)

    cocoapods {
        version = "1.0.0"
        summary = "Kuikly Network 示例页面"
        homepage = "https://github.com/catchzoon/kuikly-network"
        ios.deploymentTarget = "16.0"
        framework {
            baseName = "shared"
            isStatic = true
            binaryOption("bundleId", "com.catchzoon.network.sample.shared")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":network-core"))
            implementation(project(":network-kuikly"))
            implementation(libs.kuikly.core)
            implementation(libs.kuikly.core.annotations)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            api(libs.kuikly.render.android)
        }
    }
}

dependencies {
    compileOnly(libs.kuikly.core.ksp) {
        add("kspAndroid", this)
        add("kspIosArm64", this)
        add("kspIosSimulatorArm64", this)
    }
    add("kspAndroid", project(":network-ksp"))
    add("kspIosArm64", project(":network-ksp"))
    add("kspIosSimulatorArm64", project(":network-ksp"))
}

ksp {
    arg("pageName", providers.gradleProperty("pageName").orElse("").get())
}

android {
    namespace = "com.catchzoon.network.sample.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
