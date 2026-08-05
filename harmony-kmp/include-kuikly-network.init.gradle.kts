settingsEvaluated {
    val projectRoot = settingsDir.parentFile.parentFile
    include(":kuikly-network")
    project(":kuikly-network").projectDir = projectRoot.resolve("harmony-kmp/shared")

    include(":kuikly-network-ksp")
    project(":kuikly-network-ksp").apply {
        projectDir = projectRoot.resolve("network-ksp")
        buildFileName = "build.ohos.gradle.kts"
    }
}
