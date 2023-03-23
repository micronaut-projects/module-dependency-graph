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
    implementation("org.gradle:gradle-tooling-api:7.6.1-20230318053308+0000")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.4.0.202211300538-r")
}
