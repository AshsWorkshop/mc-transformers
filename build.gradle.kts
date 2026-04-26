import net.ashwork.gradle.multiloader.configureInheritingFeature
import net.ashwork.gradle.multiloader.publication
import net.ashwork.gradle.multiloader.publishedAccessTransformer
import net.ashwork.gradle.multiloader.resolveProperty
import java.nio.file.Files

plugins {
    java
    idea
    id("multiloader-publishing")
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val fullMinecraftVersion = if (minecraftVersion.split(".").size == 2) "${minecraftVersion}.0" else minecraftVersion
version = "${fullMinecraftVersion}.${providers.gradleProperty("${minecraftVersion}_build").get()}"

class MinecraftMetadataSupplier : ComponentMetadataSupplier {

    override fun execute(details: ComponentMetadataSupplierDetails) {
        if (details.id.group == "net.fabricmc.fabric-api" && details.id.moduleIdentifier.name == "fabric-api") {
            val minecraftVersion = details.id.version.split("+")[1]
            details.result.setStatus(minecraftVersion)
            details.result.setStatusScheme(listOf(minecraftVersion))
        }
    }
}

repositories {
    mavenCentral()
    maven {
        name = "NeoForged Maven"
        url = uri("https://maven.neoforged.net/releases")
    }
    maven {
        name = "Fabric Maven"
        url = uri("https://maven.fabricmc.net/")
        setMetadataSupplier(MinecraftMetadataSupplier::class.java)
    }
}

val transformers = configurations.create("transformers") {
    resolutionStrategy {
        cacheDynamicVersionsFor(10, TimeUnit.MINUTES)
        cacheChangingModulesFor(10, TimeUnit.MINUTES)
    }
}

dependencies {
    transformers("net.neoforged:neoforge:${fullMinecraftVersion}.+")
    transformers("net.fabricmc.fabric-api:fabric-api:latest.${minecraftVersion}")
}

val unpack = tasks.register<Task>("unpackTransformers") {
    description = "Unpacks the transformers from the artifact dependencies."
    group = "transformers"

    // Define outputs
    val taskOutput = layout.buildDirectory.dir("transformers")
    outputs.dir(taskOutput)

    transformers.incoming.artifacts.forEach {
        // Define inputs
        inputs.file(it.file)

        // Split component identifier into its directories
        val identifier = it.id.componentIdentifier.displayName
        val fileOutput = taskOutput.map {
            it.dir(identifier.substring(0, identifier.lastIndexOf(":")).replace("""[.:]""".toRegex(), "/"))
        }

        // Copy transformers into output
        copy {
            from(zipTree(it.file))
            into(fileOutput)
            include("**/*.classtweaker", "**/*.cfg")

            includeEmptyDirs = false
        }
    }
}

enum class AccessType {
    PUBLIC,
    PROTECTED,
    PACKAGE_PRIVATE,
    PRIVATE
}

sealed class Transformer(open val original: String) {
    abstract fun matches(transformer: Transformer): Boolean

    open fun intersect(against: Set<Transformer>): Set<Transformer> {
        val output = mutableSetOf<Transformer>()

        // Match transformers
        if (against.find { it.matches(this) } != null) output.add(this)

        return output
    }

    override fun toString(): String = this.original
}
data class ClassTransformer(val name: String, override val original: String): Transformer(original) {
    override fun matches(transformer: Transformer): Boolean {
        return transformer is ClassTransformer && this.name == transformer.name
    }
}
data class MethodTransformer(val name: String, val descriptor: String, override val original: String): Transformer(original) {
    override fun matches(transformer: Transformer): Boolean {
        return transformer is MethodTransformer &&
                (
                        // Either the names and descriptors match
                        (this.name == transformer.name && this.descriptor == transformer.descriptor)
                                // Or name is wildcard
                                || (this.name == "*" || transformer.name == "*")
                )
    }

    override fun intersect(against: Set<Transformer>): Set<Transformer> {
        // If not a wildcard, just return a match
        if (this.name != "*") return super.intersect(against)

        // Otherwise, resolve wildcard
        var otherHasWildcard: Boolean = false
        val output = mutableSetOf<Transformer>()
        against.filter { this.matches(it) }.map { it as MethodTransformer }.forEach { other ->
            // Skip since we can just use wildcard
            if (otherHasWildcard) return@forEach
            else if (other.name == "*") {
                otherHasWildcard = true
                return@forEach
            }

            // Resolve transformer
            output.add(MethodTransformer(
                other.name, other.descriptor,
                this.original.replace("*", other.name).replace("()", other.descriptor)
            ))
        }
        return if(otherHasWildcard) setOf(this) else output
    }
}
data class FieldTransformer(val name: String, override val original: String): Transformer(original) {
    override fun matches(transformer: Transformer): Boolean {
        return transformer is FieldTransformer && (
                        // Either the names match
                        this.name == transformer.name
                                // Or name is wildcard
                                || (this.name == "*" || transformer.name == "*")
                )
    }

    override fun intersect(against: Set<Transformer>): Set<Transformer> {
        // If not a wildcard, just return a match
        if (this.name != "*") return super.intersect(against)

        // Otherwise, resolve wildcard
        var otherHasWildcard: Boolean = false
        val output = mutableSetOf<Transformer>()
        against.filter { this.matches(it) }.map { it as FieldTransformer }.forEach { other ->
            // Skip since we can just use wildcard
            if (otherHasWildcard) return@forEach
            else if (other.name == "*") {
                otherHasWildcard = true
                return@forEach
            }

            // Resolve transformer
            output.add(FieldTransformer(
                other.name,
                this.original.replace("*", other.name)
            ))
        }
        return if(otherHasWildcard) setOf(this) else output
    }
}
class TransformerType(val name: String, val group: String, val extension: String, val reader: (File) -> Map<String, Set<Transformer>>, val header: String = "") {}

fun buildTransformerTypes(): Map<String, TransformerType> {
    val transformerTypes: MutableMap<String, TransformerType> = mutableMapOf()
    transformerTypes.computeIfAbsent("cfg") { TransformerType("accesstransformer", "accessTransformers", it, ::fromTransformer) }
    transformerTypes.computeIfAbsent("classtweaker") { TransformerType("accesswidener", "classTweakers", it, ::fromTweaker, "classTweaker  v1  official") }
    return transformerTypes
}

fun fromTweaker(file: File): Map<String, Set<Transformer>> {
    val output = mutableMapOf<String, MutableSet<Transformer>>()

    file.forEachLine { entry ->
        // Mods can only consume transitive wideners, so those are the only we include
        if (!entry.startsWith("transitive-")) {
            return@forEachLine;
        }

        val components = entry.split("""\s+""".toRegex())
        val callback: ((String) -> Transformer) -> Unit = {
            output.computeIfAbsent(components[2]) { mutableSetOf<Transformer>() }.add(it(entry))
        }

        // Get transformer type
        when (components[1]) {
            "class" -> callback { ClassTransformer(components[2], it) }
            "method" -> callback { MethodTransformer(components[3], components[4], it) }
            "field" -> callback { FieldTransformer(components[3], it) }
            else -> {
                return@forEachLine;
            }
        }
    }

    return output
}

fun fromTransformer(file: File): Map<String, Set<Transformer>> {
    val output = mutableMapOf<String, MutableSet<Transformer>>()

    file.forEachLine { entry ->
        // Ignore any whitespace or comment lines
        if (entry.trim().isEmpty() || entry.startsWith("#")) {
            return@forEachLine;
        }


        val components = entry.let {
            val commentIdx = it.lastIndexOf("#")
            if (commentIdx != -1) it.substring(0, commentIdx) else it
        }.trim().split("""\s+""".toRegex())
        val callback: ((String) -> Transformer) -> Unit = { factory ->
            output.computeIfAbsent(components[1].replace(".", "/")) { mutableSetOf<Transformer>() }.add(factory(entry))
        }

        if (components.size == 2) callback { ClassTransformer(components[1], it) }
        else if (components.size == 3) callback {
            val methodDescriptorIdx = components[2].indexOf("(")
            if (methodDescriptorIdx != -1) MethodTransformer(components[2].substring(0, methodDescriptorIdx), components[2].substring(methodDescriptorIdx), it)
            else FieldTransformer(components[2], it)
        }
    }

    return output
}

val generatedTransformers = layout.buildDirectory.dir("generated")

val allTransformers = tasks.register<Task>("allTransformers") {
    group = "transformers"
    description = "Merges the unpacked transformers into a single file."
    dependsOn(unpack)

    val transformerTypes = buildTransformerTypes()

    // Define outputs
    val taskOutput = generatedTransformers.map { it.dir("main") }
    outputs.dir(taskOutput)

    // Define inputs
    inputs.dir(layout.buildDirectory.dir("transformers"))

    // Create transformer maps
    val transformers: MutableMap<TransformerType, MutableMap<String, MutableSet<Transformer>>> = mutableMapOf()
    inputs.files.forEach {
        val type = transformerTypes[it.path.substring(it.path.lastIndexOf(".") + 1)]
        if (type == null) return@forEach

        // Read entries
        val entries = type.reader(it)

        // Merge entries into main map
        val output = transformers.computeIfAbsent(type) { mutableMapOf<String, MutableSet<Transformer>>() }
        entries.forEach { className, transformers -> output.computeIfAbsent(className) { mutableSetOf<Transformer>() }.addAll(transformers) }
    }

    // Write to a single file
    transformers.forEach { type, entries ->
        val outputFile = taskOutput.map { it.file("${type.group}/${type.name}.${type.extension}") }
        Files.createDirectories(outputFile.get().asFile.parentFile.toPath())
        outputFile.get().asFile.printWriter(Charsets.UTF_8).use { out ->
            if (type.header.isNotEmpty()) out.println(type.header)
            entries.flatMap { it.value }.map { it.original }.forEach { out.println(it) }
        }
    }
}

val computeIntersection = tasks.register<Task>("computeTransformerIntersection") {
    group = "transformers"
    description = "Computes the intersection from the unpacked transformers."
    dependsOn(unpack)

    val transformerTypes = buildTransformerTypes()

    // Define outputs
    val taskOutput = generatedTransformers.map { it.dir("common") }
    outputs.dir(taskOutput)

    // Define inputs
    inputs.dir(layout.buildDirectory.dir("transformers"))

    // Create transformer maps
    val transformers: MutableMap<TransformerType, MutableMap<String, MutableSet<Transformer>>> = mutableMapOf()
    inputs.files.forEach {
        val type = transformerTypes[it.path.substring(it.path.lastIndexOf(".") + 1)]
        if (type == null) return@forEach

        // Read entries
        val entries = type.reader(it)

        // Merge entries into main map
        val output = transformers.computeIfAbsent(type) { mutableMapOf<String, MutableSet<Transformer>>() }
        entries.forEach { className, transformers -> output.computeIfAbsent(className) { mutableSetOf<Transformer>() }.addAll(transformers) }
    }

    // Compute class intersections
    var classes: MutableSet<String>? = null
    transformers.values.forEach { it ->
        // Setup mutable set
        if (classes == null) {
            classes = mutableSetOf()
            classes.addAll(it.keys)
        }

        val intersection = classes intersect it.keys
        classes = mutableSetOf()
        classes.addAll(intersection)
    }

    // Compute transformer intersections
    val output = transformers.map { (type, classEntries) ->
        // Only include classes with intersections
        val resolvedClassEntries = classEntries.filter { it.key in classes!! }.map { (className, entries) ->
            val resolvedEntries = mutableSetOf<Transformer>()

            transformers.forEach { otherType, otherClassEntries ->
                // Skip if this
                if (otherType == type) return@forEach
                val otherTransformers = otherClassEntries[className]
                if (otherTransformers == null) return@forEach
                resolvedEntries.addAll(entries.map { it.intersect(otherTransformers) }.flatMap { it })
            }

            Pair(className, resolvedEntries)
        }.toMap()

        Pair(type, resolvedClassEntries)
    }.toMap()

    output.forEach { type, entries ->
        val outputFile = taskOutput.map { it.file("${type.group}/${type.name}.${type.extension}") }
        Files.createDirectories(outputFile.get().asFile.parentFile.toPath())
        outputFile.get().asFile.printWriter(Charsets.UTF_8).use { out ->
            if (type.header.isNotEmpty()) out.println(type.header)
            entries.flatMap { it.value }.map { it.original }.forEach { out.println(it) }
        }
    }
}

// Setup publishing
val intersect = configureInheritingFeature("common", "main", publish = true)
configureInheritingFeature("main", publish = true)

generatedTransformers.get().asFileTree.forEach {
    var components = it.toRelativeString(generatedTransformers.get().asFile).split(File.separator)
    project.publishedAccessTransformer(it, components[1], components[2].substring(0, components[2].indexOf(".")), components[0])
}

publication {
    name = resolveProperty("mod_name")
}
