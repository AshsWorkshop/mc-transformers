package net.ashwork.gradle.multiloader

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.register

/**
 * Registers a publication, configuring the pom as needed. Since this publication is component-backed, Gradle will
 * handle dependencies and artifacts, so the only configuration needed in the pom is metadata. This should be used only
 * once per project.
 */
fun Project.publication(configurePom: Action<MavenPom>) {
    extensions.configure<PublishingExtension>("publishing") {
        publications.register<MavenPublication>("mavenJava") {
            from(components.getByName("java"))
            pom(configurePom)
        }
    }
}
