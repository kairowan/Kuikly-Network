plugins {
    `java-platform`
    `maven-publish`
}

dependencies {
    constraints {
        api(project(":network-core"))
        api(project(":network-kuikly"))
        api(project(":network-inspector"))
        api(project(":network-koin"))
        api(project(":network-realtime"))
        api(project(":network-testing"))
        api(project(":network-ksp"))
    }
}

publishing { publications { create<MavenPublication>("bom") { from(components["javaPlatform"]) } } }
