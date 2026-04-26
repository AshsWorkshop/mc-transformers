import org.gradle.kotlin.dsl.support.kotlinCompilerOptions

plugins {
    `kotlin-dsl`
}

repositories {
    mavenLocal()
    maven {
        name = "NeoForged Maven"
        url = uri("https://maven.neoforged.net/releases")
    }
    maven {
        name = "Fabric Maven"
        url = uri("https://maven.fabricmc.net/")
    }
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // - https://projects.neoforged.net/neoforged/moddevgradle
    implementation("net.neoforged:moddev-gradle:2.0.141")
}
