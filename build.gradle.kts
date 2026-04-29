import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import net.ashwork.gradle.multiloader.configureInheritingFeature
import net.ashwork.gradle.multiloader.publication
import net.ashwork.gradle.multiloader.publishedAccessTransformer
import net.ashwork.gradle.multiloader.resolveProperty
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

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
data class TransformerGroup(val extension: String, val name: String, val header: String = "")
class TransformerType(val groups: List<TransformerGroup>, val reader: (File) -> Map<String, Set<Transformer>>) {}

fun buildTransformerTypes(): Map<String, TransformerType> {
    val accessTransformers = TransformerGroup("cfg", "accessTransformers")
    val classTweakers = TransformerGroup("classtweaker", "classTweakers", "classTweaker v1 official")
    val accessWideners = TransformerGroup("accesswidener", "accessWideners", "accessWidener v2 named")

    val transformerTypes: MutableMap<String, TransformerType> = mutableMapOf()
    transformerTypes.computeIfAbsent("cfg") { TransformerType(listOf(accessTransformers), ::fromTransformer) }
    transformerTypes.computeIfAbsent("classtweaker") { TransformerType(listOf(classTweakers, accessWideners), ::fromTweaker) }
    transformerTypes.computeIfAbsent("accesswidener") { TransformerType(listOf(accessWideners), ::fromTweaker) }
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
    val taskOutputNeo = generatedTransformers.map { it.dir("neoforge") }
    val taskOutputFabric = generatedTransformers.map { it.dir("fabric") }
    outputs.dir(taskOutputNeo)
    outputs.dir(taskOutputFabric)

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
        val taskOutput = if (type.groups.find { it.name == "accessTransformers" } != null) taskOutputNeo else taskOutputFabric
        type.groups.forEach { group ->
            val outputFile = taskOutput.map { it.file("${group.name}/transformer.${group.extension}") }
            Files.createDirectories(outputFile.get().asFile.parentFile.toPath())
            outputFile.get().asFile.printWriter(Charsets.UTF_8).use { out ->
                if (group.header.isNotEmpty()) out.println(group.header)
                entries.flatMap { it.value }.map { it.original }.forEach { out.println(it) }
            }
        }
    }
}

val computeIntersection = tasks.register<Task>("computeTransformerIntersection") {
    group = "transformers"
    description = "Computes the intersection from the unpacked transformers."
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
        type.groups.forEach { group ->
            val outputFile = taskOutput.map { it.file("${group.name}/transformer.${group.extension}") }
            Files.createDirectories(outputFile.get().asFile.parentFile.toPath())
            outputFile.get().asFile.printWriter(Charsets.UTF_8).use { out ->
                if (group.header.isNotEmpty()) out.println(group.header)
                entries.flatMap { it.value }.map { it.original }.forEach { out.println(it) }
            }
        }
    }
}

fun File.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(this.readBytes())
    return digest.toHexString()
}

val checkFileHash = tasks.register<Task>("checkGeneratedTransformerHashes") {
    group = "transformers"
    description = "Checks the hashes of the generated artifacts."

    // Define outputs
    val taskOutput = rootProject.file(".generated-hashes/${minecraftVersion}.json")
    outputs.file(taskOutput)

    // Define inputs
    inputs.dir(generatedTransformers)

    // Check file hashes
    var hasNewEntries = false
    val entries = if (taskOutput.exists()) taskOutput.let {
        copy {
            from(it)
            into(layout.buildDirectory.dir("compare"))
        }
        mutableMapOf<String, String>(*(JsonSlurper().parse(it) as Map<String, String>).toList().toTypedArray())
    }
    else mutableMapOf<String, String>()
    inputs.files.forEach {
        val relative = it.toRelativeString(generatedTransformers.get().asFile).replace(File.separator, "/")
        val hash = it.md5()
        if (relative !in entries || entries[relative] != hash) {
            hasNewEntries = true
        }
        entries[relative] = hash
    }
    Files.createDirectories(taskOutput.parentFile.toPath())
    FileWriter(taskOutput, StandardCharsets.UTF_8).use { it.write(JsonOutput.prettyPrint(JsonOutput.toJson(entries))) }
}

// Setup publishing
configureInheritingFeature("fabric", "main", publish = true)
configureInheritingFeature("neoforge", "main", publish = true)
configureInheritingFeature("main", publish = true)

generatedTransformers.get().asFileTree.forEach {
    var components = it.toRelativeString(generatedTransformers.get().asFile).split(File.separator)
    project.publishedAccessTransformer(it, components[1], components[0])
}

publication {
    name = resolveProperty("mod_name")
}
