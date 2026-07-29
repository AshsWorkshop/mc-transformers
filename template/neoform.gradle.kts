plugins {
    java
    id("net.neoforged.moddev") version "${mdg_version}"
}

neoForge {
    neoFormVersion = "${minecraft_version}-1"
}

repositories {
    maven {
        name = "UUID"
        // https://maven.uuid.gg/#/snapshots
        url = uri("https://maven.uuid.gg/snapshots")
    }
}

dependencies {
    accessTransformers("net.ashwork.mc:transformers:${published_version}")
}
