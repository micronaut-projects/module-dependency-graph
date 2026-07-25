plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven {
        setUrl("https://repo.gradle.org/gradle/repo")
    }
}

dependencies {
    implementation("org.gradle:gradle-tooling-api:9.0.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r")
}
