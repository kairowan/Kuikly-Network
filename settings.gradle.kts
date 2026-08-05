pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://mirrors.tencent.com/repository/maven-tencent/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://mirrors.tencent.com/repository/maven-tencent/")
    }
}

rootProject.name = "KuiklyNetwork"

include(":androidApp")
include(":shared")
include(":network-core")
include(":network-kuikly")
include(":network-inspector")
include(":network-koin")
include(":network-realtime")
include(":network-ksp")
include(":network-testing")
include(":network-bom")
