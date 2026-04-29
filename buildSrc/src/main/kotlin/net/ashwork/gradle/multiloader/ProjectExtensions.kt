package net.ashwork.gradle.multiloader

import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import net.neoforged.moddevgradle.tasks.CopyDataFile
import org.apache.tools.ant.taskdefs.Local
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.Transformer
import org.gradle.api.artifacts.ConfigurablePublishArtifact
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.Locale
import javax.inject.Inject

fun Project.resolveProperty(name: String): String {
    if (project.extra.has(name)) return project.extra[name].toString()
    return rootProject.extra[name].toString()
}

private fun Project.featureFromNotation(dependency: String): ProjectDependency {
    val splitPoint = dependency.lastIndexOf(':')
    val path = if (splitPoint == -1) this.path else ":${providers.gradleProperty("mod_id").get()}-${dependency.substring(0, splitPoint)}"
    val feature = dependency.substring(splitPoint + 1)
    
    return dependencyFactory.create(project(path)).also {
        if (feature != "main") {
            it.capabilities { requireFeature(feature) }
        }
    }
}

/**
 * @param name the name of the source set/feature to create
 * @param inherit features in this project ("name") or other projects ("api:name") to inherit from
 * @param publish whether publication bits for the feature, like bundling, are set up
 * @param bundle features to be bundled, in the same notation as [inherit]
 * @param excludeClasspathDependencies mostly necessary for the weird case of the "main" feature on fabric. To support adding MC deps to "base", this allows "main" to inherit "base" but not its classpath
 * @param depend features that the module should publish dependencies on. Necessary because of the same weirdness with the "main" feature on loom; without that, this is recoverable from [inherit] and [bundle] 
 */
fun Project.configureInheritingFeature(name: String, vararg inherit: String, publish: Boolean = false, bundle: List<String> = listOf(), excludeClasspathDependencies: Boolean = false, depend: List<String>? = null): SourceSet {
    val sourceSets: SourceSetContainer by extensions
    val java: JavaPluginExtension by extensions
    
    var sourceSet = sourceSets.findByName(name)
    if (sourceSet == null) {
        // We assume if the source set exists, then the feature also does ("main" is a good example of this)
        sourceSet = sourceSets.create(name)
        java.registerFeature(name) {
            usingSourceSet(sourceSet)
//            withSourcesJar()
            if (!publish) {
                this.disablePublication()
            }
        }
        // We need the sources jar to always exist and be visible cross-project, to create combined sources jars, but we also need to not publish it for most features
        if (!publish) {
            val java = project.components.getByName("java") as AdhocComponentWithVariants
            val sourcesElements by configurations.named(sourceSet.sourcesElementsConfigurationName)
            java.withVariantsFromConfiguration(sourcesElements) {
                skip()
            }
        }
    }

    val compileClasspath by configurations.named(sourceSet.compileClasspathConfigurationName)
    val runtimeClasspath by configurations.named(sourceSet.runtimeClasspathConfigurationName)
    val apiElements by configurations.named(sourceSet.apiElementsConfigurationName)
    val runtimeElements by configurations.named(sourceSet.runtimeElementsConfigurationName)

    // Cross-project dependencies on features are handled primarily through artifact views on this configuration; it is
    // split into a dependency scope and resolvable config because every Configuration should have exactly one scope.
    val crossProjectDependencies = configurations.dependencyScope(sourceSet.getTaskName(null, "crossProjectDependencies")).get()
    val crossProjectClasspath = configurations.resolvable(sourceSet.getTaskName(null, "crossProjectClasspath")) {
        extendsFrom(crossProjectDependencies)
        isTransitive = false
    }.get()
    
    fun addPublishedDependency(notation: String) {
        val publishedDependencies = configurations.maybeCreate(sourceSet.getTaskName(null, "justPublishedDependencies"))
        configurations.named(sourceSet.runtimeElementsConfigurationName) { extendsFrom(publishedDependencies) }
        configurations.named(sourceSet.apiElementsConfigurationName) { extendsFrom(publishedDependencies) }
        dependencies.add(publishedDependencies.name, featureFromNotation(notation))
    }
    
    if (depend != null) {
        // Should only be relevant with loom "main", due to it being impossible to properly inherit from only "main" with loom and get the things it bundles too
        for (dependency in depend) {
            addPublishedDependency(dependency)
        }
    }

    val bundlingSet = bundle.toSet()
    for (output in inherit) {
        if (publish && depend == null && !bundlingSet.contains(output)) {
            // Since this is a dependency, but is not bundled, we should provide a dependency on the relevant (presumably published) feature
            addPublishedDependency(output)
        }
        
        // At this point (as done later as well), the logic is split between in-project and cross-project dependencies.
        // In-project features are fully inherited. Cross-project features inherit output only, not classpath or published dependencies.
        // This makes life a lot simpler and is all that is needed for this as used here.
        
        if (output.contains(":")) {
            // This allows for "clean" cross-project dependencies on classes/resources.
            // We can resolve either classes or resources by making a non-transitive dependency, and then doing artifactViews
            // with the relevant library type
            dependencies.add(crossProjectDependencies.name, featureFromNotation(output))
            continue
        }

        val otherSourceSet by sourceSets.named(output)
        if (!excludeClasspathDependencies) {
            dependencies.add(sourceSet.implementationConfigurationName, otherSourceSet.output)
        }

        if (!excludeClasspathDependencies) {
            // Once again, more loom jankiness -- normally we would always want this. Except for "main" on loom, we are
            // forced to inherit the compile/runtime classpaths *backwards* because loom will only add modding deps to those. 
            compileClasspath.extendsFrom(configurations.named(otherSourceSet.compileClasspathConfigurationName))
            runtimeClasspath.extendsFrom(configurations.named(otherSourceSet.runtimeClasspathConfigurationName))
        }
        apiElements.extendsFromNoArtifacts(this, configurations.named(otherSourceSet.apiElementsConfigurationName))
        runtimeElements.extendsFromNoArtifacts(this, configurations.named(otherSourceSet.runtimeElementsConfigurationName))
    }

    // We can get the classes and resources of cross-project dependencies through artifact views on the resolved config.
    // This is the equivalent of SourceSet.output on the in-project case; it gives all the source set classes and resources.
    val crossProjectInclude = files(crossProjectClasspath.incoming.artifactView {
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.CLASSES))
    }.files, crossProjectClasspath.incoming.artifactView {
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.RESOURCES))
    }.files)
    if (!excludeClasspathDependencies) {
        dependencies.add(sourceSet.implementationConfigurationName, crossProjectInclude)
    }

    if (publish) {
        val license = listOf(project, rootProject).asSequence().map {
            // someProject.file("LICENSE") would break project isolation; this is more future-proof
            it.projectDir.resolve("LICENSE")
        }.firstOrNull { it.exists() }
        
        // This bit basically replicates some of the logic from `inherit`, just collecting different information 
        val bundleSourceSets: MutableList<SourceSet> = mutableListOf()
        val crossProjectBundleDependencies = configurations.dependencyScope(sourceSet.getTaskName(null, "crossProjectBundle")).get()
        val crossProjectBundleClasspath = configurations.resolvable(sourceSet.getTaskName(null, "crossProjectBundleClasspath")) {
            extendsFrom(crossProjectBundleDependencies)
            isTransitive = false
        }.get()

        for (output in bundle) {
            if (output.contains(":")) {
                dependencies.add(crossProjectBundleDependencies.name, featureFromNotation(output))
                continue
            }

            val otherSourceSet by sourceSets.named(output)
            bundleSourceSets.add(otherSourceSet)
        }
        
//        tasks.named(sourceSet.jarTaskName, Jar::class.java) {


//            if (license != null) {
//                from(license) {
//                    rename { "META-INF/${it}"}
//                }
//            }
//
//            for (otherSourceSet in bundleSourceSets) {
//                from(otherSourceSet.output)
//            }
//            from(files(crossProjectBundleClasspath.incoming.artifactView {
//                attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.CLASSES))
//            }.files, crossProjectBundleClasspath.incoming.artifactView {
//                attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.RESOURCES))
//            }.files))
//        }
//        tasks.named(sourceSet.sourcesJarTaskName, Jar::class.java) {
//            if (license != null) {
//                from(license) {
//                    rename { "META-INF/${it}"}
//                }
//            }
//
//            for (otherSourceSet in bundleSourceSets) {
//                from(otherSourceSet.allSource)
//            }
//            val crossProjectSourcesInclude = files(crossProjectBundleClasspath.incoming.artifactView {
//                attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.DOCUMENTATION))
//                attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType::class.java, DocsType.SOURCES))
//                // variant reselection means we can get the relevant xyzSourcesElements -- which normally an artifact
//                // view could never find, as it belongs to a different variant.
//                withVariantReselection()
//            }.files.elements.map(objects.newInstance(UnzipTransformer::class.java)))
//            from(crossProjectSourcesInclude) {
//                exclude("META-INF/MANIFEST.MF")
//            }
//        }
    }

    // Disable jar elements
    val component = project.components.getByName("java") as AdhocComponentWithVariants
    component.withVariantsFromConfiguration(apiElements) {
        skip()
    }
    component.withVariantsFromConfiguration(runtimeElements) {
        skip()
    }

    return sourceSet
}

private fun Configuration.extendsFromNoArtifacts(project: Project, named: NamedDomainObjectProvider<Configuration>) {
    // This is a tool to make one configuration "extend" from another, without copying its artifacts.
    // This allows a bundling feature to properly inherit the deps of the things it bundles, without also ending up with
    // all its artifacts and capabilities.
    val dependenciesConfiguration = project.configurations.maybeCreate(named.get().name + "Dependencies")
    dependenciesConfiguration.isCanBeResolved = false
    dependenciesConfiguration.isCanBeConsumed = false
    extendsFrom(dependenciesConfiguration)
    dependenciesConfiguration.dependencies.addAllLater(named.map { it.allDependencies })
    dependenciesConfiguration.dependencyConstraints.addAllLater(named.map { it.allDependencyConstraints })
}

// Same logic as https://github.com/neoforged/ModDevGradle/blob/846b2d70f99519640efd6620e7d6f9034fe38285/src/main/java/net/neoforged/moddevgradle/internal/DataFileCollections.java#L107-L135
// just for other features than the main one
class AccessTransformerElementHelper constructor(val project: Project, val variant: String, sourceSet: SourceSet) {
    var artifactCount: Int = 0
    var firstArtifact: ConfigurablePublishArtifact? = null
    val copyTaskName: String = sourceSet.getTaskName("copy", "${variant.replaceFirstChar { it.uppercase(Locale.ROOT) }}ElementsPublications")
    val copyTask: TaskProvider<CopyDataFile> = project.tasks.register<CopyDataFile>(copyTaskName)
    val featureName: String = sourceSet.name

    fun accept(file: File, attributeName: String, configuration: Configuration) {
        val dummyArtifact = project.artifacts.add(configuration.name, file)
        val artifactFile = dummyArtifact.file
        val artifactDependencies = dummyArtifact.buildDependencies
        configuration.artifacts.remove(dummyArtifact)

        val copyOutput = project.layout.buildDirectory.file(copyTaskName + "/" + artifactCount + "-" + artifactFile.getName())
        copyTask.configure {
            dependsOn(artifactDependencies)
            inputFiles.add(project.layout.file(project.provider { artifactFile }))
            outputFiles.add(copyOutput)
        }

        val artifactPrefix = if (featureName == "main") "" else "${featureName}-"

        project.artifacts.add(configuration.name, copyOutput) {
            builtBy(copyTask)
            val currentFirstArtifact = firstArtifact
            if (currentFirstArtifact == null) {
                firstArtifact = this
                this.classifier = "${artifactPrefix}${attributeName}"
                artifactCount = 1
            } else {
                if (artifactCount == 1) {
                    currentFirstArtifact.classifier = "${artifactPrefix}${attributeName}$artifactCount"
                }
                classifier = "${artifactPrefix}${attributeName}${(++artifactCount)}"
            }
        }
    }
}

abstract class UnzipTransformer : Transformer<FileCollection, Set<FileSystemLocation>> {
    // Simply a tool to lazily unzip a set of files in a way that can be piped into a file collection, since apparently
    // Gradle has no built-in way of doing this
    @get:Inject
    abstract val archiveOperations: ArchiveOperations
    
    @get:Inject
    abstract val objects: ObjectFactory
    
    override fun transform(input: Set<FileSystemLocation>): FileCollection {
        val output = objects.fileCollection()
        return output.from(input.map {
            archiveOperations.zipTree(it)
        })
    }
}

/**
 * Add an access transformer to MDG and publish it, but publish it in the specified feature (which need not be "main").
 */
fun Project.publishedAccessTransformer(file: File, variant: String, feature: String) {
//    val neoForge: NeoForgeExtension by extensions
//    neoForge.accessTransformers {
//        from(file.toRelativeString(project.projectDir))
//        if (feature == "main") {
//            // We can use what MDG already has here
//            publish(file)
//        }
//    }
//    if (feature != "main") {
        // MDG doesn't have built-in support for ATs in other features on the publishing side, so we have to do this
        // manually, replicating MDG's logic. Note that on the *consuming* side this will all work with MDG out-of-the-box.
        val sourceSets: SourceSetContainer by extensions
        val sourceSet by sourceSets.named(feature)
        val configurationName = sourceSet.getTaskName(null, "${variant}Elements")
        var configuration = configurations.findByName(configurationName)
        val categoryAttribute = variant.lowercase(Locale.ROOT).let { if (it.endsWith("s")) it.substring(0, it.length - 1) else it }
        if (configuration == null) {
            configuration = configurations.consumable(configurationName) {
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.CATEGORY_ATTRIBUTE.getType(), categoryAttribute));
                }
                if (feature != "main") {
                    outgoing.capability(providers.provider {"${project.group}:${project.name + "-" + feature}:${project.version}"})
                }
            }.get()
            sourceSet.extensions.add("_internal_${variant}RegistrationHelper", AccessTransformerElementHelper(this, variant, sourceSet))
            val java = project.components.getByName("java") as AdhocComponentWithVariants
            java.addVariantsFromConfiguration(configuration) {}
        }
        // This extension (logic quite similar to MDG's) serves to track multiple access transformers we may add, so
        // that their classifiers are adjusted to not overlap when published.
        val helper: AccessTransformerElementHelper = sourceSet.extensions.getByName<AccessTransformerElementHelper>("_internal_${variant}RegistrationHelper")
        helper.accept(file, categoryAttribute, configuration)
//    }
}
