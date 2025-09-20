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
    implementation("org.gradle:gradle-tooling-api:9.2.0-20250920003919+0000")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
}
