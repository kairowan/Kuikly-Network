plugins {
    alias(libs.plugins.kotlinJvm)
    `maven-publish`
}

java { withSourcesJar() }

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
