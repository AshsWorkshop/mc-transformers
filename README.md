# Transformers

A utility for generating access transformers / wideners from Minecraft mod loaders for use in multi-loader projects.

## Repositories

The transformers can be found in the following repositories:

<details open>

<summary>build.gradle</summary>

```groovy
repositories {
    maven {
        name = 'UUID'
        url = 'https://maven.uuid.gg/snapshots'
    }
    maven {
        name = 'Transformers Github'
        url = 'https://maven.pkg.github.com/AshsWorkshop/mc-transformers'
        // Credentials are required to pull from Github Packages (requires 'read:packages' scope)
        // See: https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry#using-a-published-package
        credentials {
            username = '<GITHUB_USERNAME>'
            password = '<GITHUB_ACCESS_TOKEN>'
        }
    }
}
```

</details>

<details>

<summary>build.gradle.kts</summary>

```kotlin
repositories {
    maven {
        name = "UUID"
        // https://maven.uuid.gg/#/snapshots
        url = uri("https://maven.uuid.gg/snapshots")
    }
    maven {
        name = "Transformers Github"
        url = uri("https://maven.pkg.github.com/AshsWorkshop/mc-transformers")
        // Credentials are required to pull from Github Packages (requires 'read:packages' scope)
        // See: https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry#using-a-published-package
        credentials {
            username = "<GITHUB_USERNAME>"
            password = "<GITHUB_ACCESS_TOKEN>"
        }
    }
}
```

</details>

## Supported Minecraft Versions

The transformer versions are constructed using the version number for Minecraft appended with a build number (e.g. transformers for `26.1.2` would be `26.1.2.0`, `26.1.2.1`, etc.). If the version number for Minecraft does not have a third component, it must be set to `0` (e.g. transformers for `26.1` would be `26.1.0.0`, `26.1.0.1`, etc.).

Transformers versions are broken into three priorities:

* Latest: Transformers for all patches of the latest Minecraft minor release will be generated once a week.
* Supported: Transformers for the latest patch of previous Minecraft minor releases (up to two years) will be generated once a month.
* End-of-Life: Transformers for the latest patch of previous Minecraft minor releases (longer than two years) will be generated once a year.

### Latest

| Minecraft Version |                                                                                               Transformer Version                                                                                                |
|:---:|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
| 26.2 |  ![26.2 Maven Version](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.uuid.gg%2Fsnapshots%2Fnet%2Fashwork%2Fmc%2Ftransformers%2Fmaven-metadata.xml&filter=26.2.0.*&cacheSeconds=43200)  |
| 26.1.2 | ![26.1.2 Maven Version](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.uuid.gg%2Fsnapshots%2Fnet%2Fashwork%2Fmc%2Ftransformers%2Fmaven-metadata.xml&filter=26.1.2.*&cacheSeconds=43200) |
| 26.1.1 | ![26.1.1 Maven Version](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.uuid.gg%2Fsnapshots%2Fnet%2Fashwork%2Fmc%2Ftransformers%2Fmaven-metadata.xml&filter=26.1.1.*&cacheSeconds=43200) |
| 26.1 |  ![26.1 Maven Version](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.uuid.gg%2Fsnapshots%2Fnet%2Fashwork%2Fmc%2Ftransformers%2Fmaven-metadata.xml&filter=26.1.0.*&cacheSeconds=43200)  |

## Using the Transformers

### [NeoForm via ModDevGradle](https://github.com/neoforged/ModDevGradle#vanilla-mode) (Recommended)

<details open>

<summary>build.gradle</summary>

```groovy
dependencies {
    accessTransformers 'net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+'
}
```

</details>

<details>

<summary>build.gradle.kts</summary>

```kotlin
dependencies {
    accessTransformers("net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+")
}
```

</details>

### [NeoGradle](https://github.com/neoforged/NeoGradle)

<details open>

<summary>build.gradle</summary>

```groovy
dependencies {
    accessTransformer 'net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+'
}
```

</details>

<details>

<summary>build.gradle.kts</summary>

```kotlin
dependencies {
    accessTransformer("net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+")
}
```

</details>

### [Fabric Loom](https://github.com/FabricMC/fabric-loom)

<details open>

<summary>build.gradle</summary>

```groovy
dependencies {
    implementation 'net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+'
}
```

</details>

<details>

<summary>build.gradle.kts</summary>

```kotlin
dependencies {
    implementation("net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+")
}
```

</details>

### [VanillaGradle](https://github.com/SpongePowered/VanillaGradle) (Deprecated)

You should not be using VanillaGradle in multiloader projects. Use one of the above entries instead. This example is provided for legacy support and is bad due to the limitations of the plugin. 

<details>

<summary>build.gradle</summary>

```groovy
// Create the configuration to get the access wideners
configurations {
    accessWideners {
        attributes {
            attribute(
                Category.CATEGORY_ATTRIBUTE,
                objects.named(Category, 'accesswidener')
            )
        }
    }
}

dependencies {
    // Add the dependency
    accessWideners 'net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+'
}

afterEvaluate {
    // Pass the access transformers to VanillaGradle
    extensions.getByName('minecraft')
            .accessWideners(*accessWideners.getIncoming.getArtifacts.collect { it.getFile })
}
```

</details>

<details>

<summary>build.gradle.kts</summary>

```kotlin
// Create the configuration to get the access wideners
val accessWideners = configurations.create("accessWideners") {
    attributes {
        attribute(
            Category.CATEGORY_ATTRIBUTE, 
            objects.named(Category.CATEGORY_ATTRIBUTE.getType(), "accesswidener")
        )
    }
}

dependencies {
    // Add the dependency
    accessWideners("net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+")
}

afterEvaluate {
    // Pass the access transformers to VanillaGradle
    extensions.getByName<MinecraftExtension>("minecraft")
        .accessWideners(*accessWideners.incoming.artifacts.map { it.file }.toTypedArray())
}
```

</details>

## Attributes

Transformers publishes the following three category attributes:

|      Attribute      | Supported By                                                                                                                         |
|:-------------------:|:-------------------------------------------------------------------------------------------------------------------------------------|
| `accesstransformer` | [NeoForge](https://github.com/neoforged/accesstransformers), [MinecraftForge](https://github.com/MinecraftForge/AccessTransformers)  |
| `accesswidener`     | [Fabric](https://github.com/FabricMC/fabric-tooling)\*, [VanillaGradle](https://github.com/SpongePowered/VanillaGradle) |
| `classtweaker` | [Fabric](https://github.com/FabricMC/fabric-tooling) |

\* `accesswidener` is deprecated for Fabric in favor of `classtweaker`

These attributes can be selectively depended upon by setting the `attributes` block of the desired configuration, assuming your modding tool does not already provide support.

<details open>

<summary>build.gradle</summary>

```groovy
configurations {
    // Create the configuration, or use an existing one if present
    transformers {
        attributes {
            attribute(
                Category.CATEGORY_ATTRIBUTE,
                // Select the attribute you would like to use
                objects.named(Category, '<attribute>')
            )
        }
    }
}

dependencies {
    // Add the dependency to the appropriate configuration
    transformers 'net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+'
}
```

</details>

<details>

<summary>build.gradle.kts</summary>

```kotlin
val transformers = configurations.create("transformers") {
    attributes {
        attribute(
            Category.CATEGORY_ATTRIBUTE,
            // Select the attribute you would like to use
            objects.named(Category.CATEGORY_ATTRIBUTE.getType(), "<attribute>")
        )
    }
}

dependencies {
    // Add the dependency to the appropriate configuration
    transformers("net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+")
}
```

</details>

### Fabric Mod JARs



## Available Features

Transformers publishes three features, each providing a different selection of entries:

| Feature | Supported Attributes | Description |
|:---:|:---:|:---|
| Default | `accesstransformer`, `accesswidener`, `classtweaker` | The common transformer entries between [NeoForge](https://github.com/neoforged/NeoForge) and [fabric-api](https://github.com/FabricMC/fabric-api). |
| `fabric` | `accesswidener`, `classtweaker` | All transformer entries from [fabric-api](https://github.com/FabricMC/fabric-api). |
| `neoforge` | `accesstransformer` | All transformer entries from [NeoForge](https://github.com/neoforged/NeoForge). |

These features can be selectively depended upon by specifying `requireFeature` in the `capabilities` block of the dependency. No `capabilities` block is required for the 'Default' transformer feature.

<details open>

<summary>build.gradle</summary>

```groovy
dependencies {
    // Add the dependency to the appropriate configuration
    transformers('net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+') {
        capabilities {
            // Select the feature you want the transformer entries for
            requireFeature '<feature>'
        }
    }
}
```

</details>

<details>

<summary>build.gradle.kts</summary>

```kotlin
dependencies {
    // Add the dependency to the appropriate configuration
    transformers("net.ashwork.mc:transformers:<MINECRAFT_VERSION>.+") {
        capabilities {
            // Select the feature you want the transformer entries for
            requireFeature("<feature>")
        }
    }
}
```

</details>
