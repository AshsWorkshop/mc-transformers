import groovy.json.JsonSlurper
import net.ashwork.gradle.multiloader.*
import org.gradle.api.Action
import org.gradle.api.publish.maven.MavenPomLicense
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.`maven-publish`
import org.gradle.kotlin.dsl.withType

plugins {
    `maven-publish`
}

group = resolveProperty("mod_group")

publishing {
    repositories {
        maven {
            name = "GitHub"
            url = uri("https://maven.pkg.${resolveProperty("mod_repository")}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
//        maven {
//            name = "UUID"
//            url = uri("https://maven.uuid.gg/${
//                "git log -1 --pretty=%B".runCommand(workingDir = rootProject.rootDir, orElse = "").let {
//                    if ("""^[^\(\)]+\([^\(\)]*release(?:(?!\[)|\[( *[a-zA-Z0-9]+(?:, *[a-zA-Z0-9]+)*) *\])[^\(\)]*\):.*$""".toRegex().matches(it))
//                        "releases"
//                    else "snapshots"
//                }
//            }")
//            credentials {
//                username = System.getenv("UUID_USERNAME")
//                password = System.getenv("UUID_PASSWORD")
//            }
//        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name = resolveProperty("mod_name")
            description = resolveProperty("mod_description")
            url = "https://${resolveProperty("mod_repository")}"
            licenses {
                license {
                    name = "N/A"
                    comments = "Transformers are not copyrightable."
                }
            }
            issueManagement {
                url = "https://${resolveProperty("mod_repository")}/issues"
                system = resolveProperty("mod_issue_system")
            }
            developers {
                resolveProperty("mod_authors").split(",").forEach {
                    developer {
                        name = it.trim()
                        organization = resolveProperty("mod_organization")
                    }
                }
            }
            scm {
                connection = "scm:git:git://${resolveProperty("mod_repository")}.git"
                developerConnection = "scm:git:ssh://${resolveProperty("mod_repository")}.git"
                url = "https://${resolveProperty("mod_repository")}"
                try {
                    tag = "git rev-parse --verify HEAD".runCommand(workingDir = rootProject.rootDir)
                } catch (e: Exception) {
                    project.logger.warn("Unable to set tag for scm in artifact pom: ${e.message}")
                }
            }
        }
    }
}