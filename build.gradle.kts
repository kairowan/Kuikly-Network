import kotlinx.validation.ExperimentalBCVApi
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.binaryCompatibilityValidator)
}

apiValidation {
    ignoredProjects.addAll(listOf("androidApp", "shared", "network-bom"))
    @OptIn(ExperimentalBCVApi::class)
    klib { enabled = true }
}

allprojects {
    group = "com.catchzoon.network"
    version = providers.gradleProperty("networkVersion")
        .orElse(providers.environmentVariable("NETWORK_VERSION"))
        .orElse("0.1.0-SNAPSHOT")
        .get()
}

subprojects {
    pluginManager.withPlugin("maven-publish") {
        pluginManager.apply("signing")
        extensions.configure<PublishingExtension> {
            publications.withType(MavenPublication::class.java).configureEach {
                pom {
                    name.set("Kuikly Network ${project.name}")
                    description.set("面向 KMP、Kuikly、Android、iOS 与 HarmonyOS 的可组合网络基础设施")
                    url.set("https://github.com/kairowan/Kuikly-Network")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm { url.set("https://github.com/kairowan/Kuikly-Network") }
                    developers {
                        developer {
                            id.set("catchzoon")
                            name.set("Catchzoon Mobile Team")
                        }
                    }
                    issueManagement {
                        system.set("GitHub")
                        url.set("https://github.com/kairowan/Kuikly-Network/issues")
                    }
                }
            }

            val repositoryUrl = providers.gradleProperty("networkRepositoryUrl")
                .orElse(providers.environmentVariable("NETWORK_REPOSITORY_URL"))
                .orNull
            val repositoryUsername = providers.environmentVariable("NETWORK_REPOSITORY_USERNAME").orNull
            if (!repositoryUrl.isNullOrBlank()) {
                repositories.maven {
                    name = "network"
                    url = uri(repositoryUrl)
                    if (!repositoryUsername.isNullOrBlank()) {
                        credentials {
                            username = repositoryUsername
                            password = providers.environmentVariable("NETWORK_REPOSITORY_PASSWORD").orNull
                        }
                    }
                }
            }
        }

        extensions.configure<SigningExtension> {
            val signingKey = providers.environmentVariable("NETWORK_SIGNING_KEY").orNull
            val signingPassword = providers.environmentVariable("NETWORK_SIGNING_PASSWORD").orNull
            if (!signingKey.isNullOrBlank()) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(extensions.getByType(PublishingExtension::class.java).publications)
            }
        }
    }
}

val jitPackModules = listOf(
    "network-core",
    "network-kuikly",
    "network-inspector",
    "network-koin",
    "network-realtime",
    "network-testing",
)

tasks.register("publishJitPackToMavenLocal") {
    group = "publishing"
    description = "Publishes JitPack's KMP metadata, Android, KSP and BOM artifacts to Maven Local"
    // ponytail: JitPack runs on Linux, so Apple KLibs stay in the macOS-built GitHub Release bundle.
    dependsOn(jitPackModules.flatMap { module ->
        listOf(
            ":$module:publishKotlinMultiplatformPublicationToMavenLocal",
            ":$module:publishAndroidReleasePublicationToMavenLocal",
        )
    })
    dependsOn(
        ":network-ksp:publishMavenPublicationToMavenLocal",
        ":network-bom:publishBomPublicationToMavenLocal",
    )
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://mirrors.tencent.com/repository/maven-tencent/")
    }
    dependencies {
        classpath("com.tencent.kuikly-open:core-gradle-plugin:2.24.0-2.1.21")
    }
}
