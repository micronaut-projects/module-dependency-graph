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
    implementation("org.gradle:gradle-tooling-api:8.4-20230711222804+0000")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.5.0.202303070854-r")
}
