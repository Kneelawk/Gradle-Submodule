/*
 * MIT License
 *
 * Copyright (c) 2023 Kneelawk.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.kneelawk.submodule

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask
import net.fabricmc.loom.task.RemapSourcesJarTask
import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.utils.extendsFrom
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties

class SubmodulePlugin : Plugin<Project> {
    companion object {
        private val metadataFiles = listOf(
            "quilt.mod.json",
            "fabric.mod.json",
            "META-INF/mods.toml",
            "META-INF/neoforge.mods.toml",
            "pack.mcmeta"
        )
    }

    override fun apply(project: Project) {
        val props = Properties()
        props.load(javaClass.classLoader.getResourceAsStream("com/kneelawk/submodule/plugin.properties"))
        val pluginVersion: String by props
        println("Submodule version: $pluginVersion")

        project.plugins.apply("org.gradle.java-library")

        val baseEx = project.extensions.getByType(BasePluginExtension::class)
        val javaEx = project.extensions.getByType(JavaPluginExtension::class)

        val javaVersion = if (System.getenv("JAVA_VERSION") != null) {
            System.getenv("JAVA_VERSION")
        } else {
            project.getProperty<String>("java_version")
        }

        val platformStr = project.getProperty<String>("submodule.platform")
        val platform = when (platformStr) {
            "xplat" -> Platform.XPLAT
            "mojmap" -> Platform.MOJMAP
            "fabric" -> Platform.FABRIC
            "neoforge" -> Platform.NEOFORGE
            else -> throw IllegalArgumentException("Unrecognized submodule.platform type: $platformStr")
        }

        if (platform == Platform.NEOFORGE) {
            (project.properties as MutableMap<String, String>)["loom.platform"] = "neoforge"
        }

        val modId = project.getProperty<String>("mod_id")

        val submoduleModeStr = project.findProperty("submodule.mode") as? String ?: "platform"
        val submoduleMode = when (submoduleModeStr.lowercase()) {
            "platform" -> SubmoduleMode.PLATFORM
            "architectury", "arch" -> SubmoduleMode.ARCHITECTURY
            else -> throw IllegalArgumentException("Unrecognized submodule.mode: $submoduleModeStr")
        }

        // apply plugins
        val loom: Boolean
        if (submoduleMode == SubmoduleMode.ARCHITECTURY) {
            project.plugins.apply("dev.architectury.loom")
            loom = true
        } else {
            if (platform == Platform.NEOFORGE) {
                project.plugins.apply("net.neoforged.moddev")
                loom = false
            } else {
                project.plugins.apply("fabric-loom")
                loom = true
            }
        }

        project.extensions.create(
            "submodule", SubmoduleExtension::class, project, javaVersion, platform, submoduleMode, modId
        )

        val mavenGroup = project.getProperty<String>("maven_group")
        project.group = mavenGroup
        val archivesBaseName = project.getProperty<String>("archives_base_name")
        baseEx.archivesName.set("${archivesBaseName}-${project.name}")

        javaEx.apply {
            sourceCompatibility = JavaVersion.toVersion(javaVersion)
            targetCompatibility = JavaVersion.toVersion(javaVersion)

            toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))

            withSourcesJar()
        }

        project.configurations {
            named("testCompileClasspath").extendsFrom(named("compileClasspath"))
            named("testRuntimeClasspath").extendsFrom(named("runtimeClasspath"))
        }

        project.repositories {
            mavenCentral()
            maven("https://maven.quiltmc.org/repository/release") { name = "Quilt" }
            maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
//            maven("https://maven.firstdark.dev/snapshots") { name = "FirstDark" }
            maven("https://maven.kneelawk.com/releases/") { name = "Kneelawk" }
            maven("https://maven.alexiil.uk/") { name = "AlexIIL" }
            maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
            maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
            maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin" }

            // manage neoforge pr repos
            if (platform == Platform.NEOFORGE) {
                val neoforgePr = project.findProperty("neoforge_pr") as? String ?: "none"
                val neoforgePrNum = neoforgePr.toIntOrNull()
                if (neoforgePrNum != null) {
                    maven("https://prmaven.neoforged.net/NeoForge/pr${neoforgePrNum}") {
                        name = "NeoForge PR #${neoforgePrNum}"
                        content {
                            includeModule("net.neoforged", "testframework")
                            includeModule("net.neoforged", "neoforge")
                        }
                    }
                }
            }

            mavenLocal()
        }

        project.dependencies {
            if (loom) {
                val loomEx = project.extensions.getByType(LoomGradleExtensionAPI::class)

                val minecraftVersion = project.getProperty<String>("minecraft_version")
                add("minecraft", "com.mojang:minecraft:$minecraftVersion")

                val mappingsType = (project.findProperty("mappings_type") as? String)?.lowercase() ?: "mojmap"
                when (mappingsType) {
                    "mojmap" -> {
                        val parchmentMcVersion = project.getProperty<String>("parchment_mc_version")
                        val parchmentVersion = project.getProperty<String>("parchment_version")
                        add("mappings", loomEx.layered {
                            officialMojangMappings {
                                nameSyntheticMembers = true
                            }
                            parchment("org.parchmentmc.data:parchment-$parchmentMcVersion:$parchmentVersion@zip")
                        })
                    }
                    "yarn" -> {
                        val yarnVersion = project.getProperty<String>("yarn_version")
                        val yarnPatch = project.getProperty<String>("yarn_patch")
                        add("mappings", loomEx.layered {
                            mappings("net.fabricmc:yarn:$minecraftVersion+build.$yarnVersion:v2")
                            mappings("dev.architectury:yarn-mappings-patch-neoforge:$yarnPatch")
                        })
                    }
                }

                when (platform) {
                    Platform.XPLAT, Platform.MOJMAP -> {
                        val fabricLoaderVersion = project.getProperty<String>("fabric_loader_version")
                        add("modCompileOnly", "net.fabricmc:fabric-loader:$fabricLoaderVersion")
                        add("modLocalRuntime", "net.fabricmc:fabric-loader:$fabricLoaderVersion")
                    }
                    Platform.FABRIC -> {
                        val fabricLoaderVersion = project.getProperty<String>("fabric_loader_version")
                        add("modCompileOnly", "net.fabricmc:fabric-loader:$fabricLoaderVersion")
                        add("modLocalRuntime", "net.fabricmc:fabric-loader:$fabricLoaderVersion")

                        val fapiVersion = project.getProperty<String>("fapi_version")
                        add("modCompileOnly", "net.fabricmc.fabric-api:fabric-api:$fapiVersion")
                        add("modLocalRuntime", "net.fabricmc.fabric-api:fabric-api:$fapiVersion")
                    }
                    Platform.NEOFORGE -> {
                        val neoforgeVersion = project.getProperty<String>("neoforge_version")
                        add("neoForge", "net.neoforged:neoforge:$neoforgeVersion")
                    }
                }
            }

            add("compileOnly", "com.google.code.findbugs:jsr305:3.0.2")
            add("testCompileOnly", "com.google.code.findbugs:jsr305:3.0.2")

            add("testImplementation", platform("org.junit:junit-bom:5.10.2"))
            add("testImplementation", "org.junit.jupiter:junit-jupiter")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        }

        val sourceSets = project.extensions.getByType(SourceSetContainer::class)

        if (loom) {
            // do loom stuff
            val loomEx = project.extensions.getByType(LoomGradleExtensionAPI::class)

            if (loomEx.mods.findByName("main") == null) {
                loomEx.mods.create(modId) {
                    sourceSet(sourceSets.named("main").get())
                }
            }

            if (platform == Platform.XPLAT) {
                project.tasks.named("jar", Jar::class).configure {
                    manifest {
                        attributes("Fabric-Loom-Remap" to true)
                    }
                }
            }

            val mappingsType = (project.findProperty("mappings_type") as? String)?.lowercase() ?: "mojmap"
            if (platform == Platform.MOJMAP && mappingsType == "mojmap") {
                project.tasks {
                    named("remapJar", RemapJarTask::class).configure {
                        targetNamespace.set("named")
                    }
                    named("remapSourcesJar", RemapSourcesJarTask::class).configure {
                        targetNamespace.set("named")
                    }
                }
            }
        } else if (platform == Platform.NEOFORGE) {
            // do moddev stuff
            val neoforgeEx = project.extensions.getByType<NeoForgeExtension>()

            neoforgeEx.version.set(project.getProperty<String>("neoforge_version"))

            neoforgeEx.parchment {
                mappingsVersion.set(project.getProperty<String>("parchment_version"))
                minecraftVersion.set(project.getProperty<String>("parchment_mc_version"))
            }

            neoforgeEx.mods {
                create(modId) {
                    sourceSet(sourceSets.named("main").get())
                }
            }
        }

        project.tasks {
            named("processResources", ProcessResources::class.java).configure {
                val properties = mapOf(
                    "version" to project.version,
                    "mod_id" to modId
                )

                inputs.properties(properties)

                filesMatching(metadataFiles) {
                    expand(properties)
                }

                exclude("**/*.xcf")
                exclude("**/*.bbmodel")
            }

            withType<JavaCompile>().configureEach {
                options.encoding = "UTF-8"
                options.release.set(javaVersion.toInt())
            }

            named("jar", Jar::class.java).configure {
                from(project.rootProject.file("LICENSE")) {
                    rename { "${it}_${project.rootProject.name}" }
                }
                archiveClassifier.set("")
            }

            named("sourcesJar", Jar::class.java).configure {
                from(project.rootProject.file("LICENSE")) {
                    rename { "${it}_${project.rootProject.name}" }
                }
            }

            val processLinksDir = project.layout.buildDirectory.dir("processed-links")
            val processLinksOutput = processLinksDir.map { it.file("javadoc-links.txt") }
            val processLinks = create("processLinks", ProcessResources::class.java) {
                from(project.rootProject.file("javadoc-links.txt"))
                into(processLinksDir)

                inputs.properties(project.properties)

                expand(project.properties)
            }

            named("javadoc", Javadoc::class.java).configure {
                dependsOn(processLinks)

                val minecraftVersion = project.getProperty<String>("minecraft_version")
                val mappingsType = project.findProperty("mappings_type") as? String ?: "mojmap"

                val baseLinks = listOf(
                    "https://guava.dev/releases/32.1.2-jre/api/docs/",
                    "https://www.javadoc.io/doc/com.google.code.gson/gson/2.10.1/",
                    "https://logging.apache.org/log4j/2.x/javadoc/log4j-api/",
                    "https://www.slf4j.org/apidocs/",
                    "https://javadoc.lwjgl.org/",
                    "https://fastutil.di.unimi.it/docs/",
                    "https://javadoc.scijava.org/JOML/",
                    "https://netty.io/4.1/api/",
                    "https://www.oshi.ooo/oshi-core-java11/apidocs/",
                    "https://java-native-access.github.io/jna/5.13.0/javadoc/",
                    "https://unicode-org.github.io/icu-docs/apidoc/released/icu4j/",
                    "https://jopt-simple.github.io/jopt-simple/apidocs/",
                    "https://solutions.weblite.ca/java-objective-c-bridge/docs/",
                    "https://commons.apache.org/proper/commons-logging/apidocs/",
                    "https://commons.apache.org/proper/commons-lang/javadocs/api-release/",
                    "https://commons.apache.org/proper/commons-io/apidocs/",
                    "https://commons.apache.org/proper/commons-codec/archives/1.15/apidocs/",
                    "https://commons.apache.org/proper/commons-compress/apidocs/",
                    "https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/apidocs/",
                    "https://docs.oracle.com/en/java/javase/21/docs/api/"
                )

                val minecraftLinks = when (mappingsType) {
                    "mojmap" -> {
                        val parchmentMcVersion = project.getProperty<String>("parchment_mc_version")
                        val parchmentVersion = project.getProperty<String>("parchment_version")
                        val javadocBuild = project.findProperty("javadoc_build") as? String ?: "1"
                        listOf(
                            "https://maven.kneelawk.com/javadoc/releases/com/kneelawk/javadoc-mc/javadoc-mc-mojmap-vanilla-loom/${minecraftVersion}+parchment.${parchmentMcVersion}-${parchmentVersion}-build.${javadocBuild}/raw/"
                        )
                    }
                    "yarn" -> {
                        val yarnVersion = project.getProperty<String>("yarn_version")
                        listOf("https://maven.fabricmc.net/docs/yarn-${minecraftVersion}+build.${yarnVersion}/")
                    }
                    else -> listOf()
                }

                val jbAnnotationsVersion = project.findProperty("jetbrains_annotations_version") as? String ?: "24.0.0"
                val jbAnnotationsLinks = listOf(
                    "https://javadoc.io/doc/org.jetbrains/annotations/${jbAnnotationsVersion}/"
                )

                (options as? StandardJavadocDocletOptions)?.apply {
                    addStringOption("-link-modularity-mismatch", "info")

                    doFirst {
                        val processLinksFile = processLinksOutput.get().asFile
                        val loadedLinks = if (processLinksFile.exists()) {
                            processLinksOutput.get().asFile.readText().split('\n').map { it.trim() }
                        } else {
                            listOf()
                        }
                        val collectedLinks = baseLinks + minecraftLinks + jbAnnotationsLinks + loadedLinks
                        links = filterConnectable(collectedLinks)
                    }
                }
            }

            named("test", Test::class.java).configure {
                useJUnitPlatform()

                maxHeapSize = "1G"

                testLogging {
                    events("passed", "skipped", "failed")
                }
            }
        }

        project.afterEvaluate {
            tasks.findByName("genSources")?.apply { setDependsOn(listOf("genSourcesWithVineflower")) }
        }
    }

    private fun filterConnectable(links: List<String>): List<String> {
        return links.filter { link ->
            val link2 = if (link.endsWith('/')) link else "$link/"
            val res = checkLink("${link2}element-list") || checkLink("${link2}package-list")

            if (!res) {
                println("Skipping ($link) due to connection errors")
            }

            res
        }
    }

    private fun checkLink(link: String): Boolean {
        try {
            val url = URI(link).toURL()
            val huc = url.openConnection() as HttpURLConnection
            huc.instanceFollowRedirects = true
            val text = huc.inputStream.use { it.bufferedReader().readText() }
            return text.isNotEmpty()
        } catch (e: Exception) {
            return false
        }
    }
}
