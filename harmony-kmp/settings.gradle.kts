pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        maven("https://mirrors.tencent.com/nexus/repository/gradle-plugins/")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
    }
}

// 此文件仅保留 IDE 索引入口；命令行构建使用 Kuikly 官方 OHOS settings 与 include init script，
// 这样官方 buildSrc 中的 MavenConfig、Version 等约定不会丢失。
rootProject.name = "KuiklyNetworkHarmonyShared"
