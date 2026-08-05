plugins {
    `java-platform`
    `maven-publish`
}

dependencies {
    constraints {
        val publishedGroup = if (providers.gradleProperty("jitpackRelease").map(String::toBoolean).getOrElse(false)) {
            "com.github.kairowan.Kuikly-Network"
        } else {
            project.group.toString()
        }
        listOf(
            "network-core",
            "network-kuikly",
            "network-inspector",
            "network-koin",
            "network-realtime",
            "network-testing",
            "network-ksp",
        ).forEach { module -> api("$publishedGroup:$module:${project.version}") }
    }
}

publishing { publications { create<MavenPublication>("bom") { from(components["javaPlatform"]) } } }
