import org.gradle.api.credentials.PasswordCredentials

plugins {
    id("java-gradle-plugin")
    kotlin("jvm")
    `kotlin-dsl`
    `maven-publish`
}

val releaseTag = System.getenv("RELEASE_TAG")
val pluginVersion = if (releaseTag != null) {
    val pluginVersion = releaseTag.substring(1)
    println("Detected Release Version: $pluginVersion")
    pluginVersion
} else {
    val plugin_version: String by project
    println("Detected Local Version: $plugin_version")
    plugin_version
}

if (pluginVersion.isEmpty()) {
    throw IllegalStateException("Failed to detect version")
}

version = pluginVersion
group = "com.kneelawk"

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.architectury.dev/") { name = "Architectury" }
    maven("https://maven.quiltmc.org/repository/release") { name = "Quilt" }
    maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
    maven("https://repo.sleeping.town/") { name = "SleepingTown" }
    maven("https://kneelawk.com/maven") { name = "Kneelawk" }
}

dependencies {
    val architectury_loom_version: String by project
    implementation("dev.architectury.loom:dev.architectury.loom.gradle.plugin:$architectury_loom_version")

    val moddev_version: String by project
    implementation("net.neoforged.moddev:net.neoforged.moddev.gradle.plugin:$moddev_version")
    
    val minivan_version: String by project
    implementation("agency.highlysuspect.minivan:agency.highlysuspect.minivan.gradle.plugin:$minivan_version")

    val kotlin_version: String by project
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:$kotlin_version")
}

tasks {
    processResources {
        inputs.property("version", project.version)
        
        filesMatching("**/plugin.properties") {
            expand("version" to project.version)
        }
    }
}

gradlePlugin {
    plugins {
        create("submodulePlugin") {
            id = "com.kneelawk.submodule"
            implementationClass = "com.kneelawk.submodule.SubmodulePlugin"
        }
    }
}

java {
    withSourcesJar()
}

publishing {
    repositories {
        val publishRepo = System.getenv("PUBLISH_REPO")
        if (publishRepo != null) {
            maven {
                name = "publishRepo"
                url = uri(rootProject.file(publishRepo))
            }
        }
        if (project.hasProperty("kneelawkUsername") && project.hasProperty("kneelawkPassword")) {
            maven {
                name = "kneelawk"
                url = project.uri("https://maven.kneelawk.com/releases")
                credentials(PasswordCredentials::class)
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
    }
}
