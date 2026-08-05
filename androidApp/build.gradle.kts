plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

val sampleBaseUrl = providers.gradleProperty("sample.baseUrl")
    .orElse(providers.environmentVariable("KUIKLY_NETWORK_SAMPLE_BASE_URL"))
    .orElse("https://httpbun.com")
val defaultBaseUrl = providers.gradleProperty("sample.defaultBaseUrl")
    .orElse(providers.environmentVariable("KUIKLY_NETWORK_DEFAULT_BASE_URL"))
    .orElse("https://example.com")

android {
    namespace = "com.catchzoon.network.sample.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.catchzoon.network.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "DEFAULT_BASE_URL", "\"" + defaultBaseUrl.get() + "\"")
        buildConfigField("String", "SAMPLE_BASE_URL", "\"" + sampleBaseUrl.get() + "\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kuikly.render.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}
