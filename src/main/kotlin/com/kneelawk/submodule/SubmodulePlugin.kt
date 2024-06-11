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
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources
import java.net.HttpURLConnection
import java.net.URI

class SubmodulePlugin : Plugin<Project> {
    private val metadataFiles = listOf(
        "quilt.mod.json",
        "fabric.mod.json",
        "META-INF/mods.toml",
        "META-INF/neoforge.mods.toml",
        "pack.mcmeta"
    )

    override fun apply(project: Project) {
        project.plugins.apply("dev.architectury.loom")

        val baseEx = project.extensions.getByType(BasePluginExtension::class)
        val javaEx = project.extensions.getByType(JavaPluginExtension::class)
        val loomEx = project.extensions.getByType(LoomGradleExtensionAPI::class)

        val javaVersion = if (System.getenv("JAVA_VERSION") != null) {
            System.getenv("JAVA_VERSION")
        } else {
            project.getProperty<String>("java_version")
        }

        project.extensions.create("submodule", SubmoduleExtension::class, project, javaVersion)

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

        project.repositories.apply {
            mavenCentral()
            maven("https://maven.quiltmc.org/repository/release") { name = "Quilt" }
            maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
            maven("https://maven.firstdark.dev/snapshots") { name = "FirstDark" }
            maven("https://maven.kneelawk.com/releases/") { name = "Kneelawk" }
            maven("https://maven.alexiil.uk/") { name = "AlexIIL" }
            maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
            maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
            maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin" }

            mavenLocal()
        }

        project.dependencies.apply {
            val minecraftVersion = project.getProperty<String>("minecraft_version")
            add("minecraft", "com.mojang:minecraft:$minecraftVersion")

            val mappingsType = project.findProperty("mappings_type") as? String ?: "mojmap"

            when (mappingsType) {
                "mojmap" -> {
                    val parchmentMcVersion = project.getProperty<String>("parchment_mc_version")
                    val parchmentVersion = project.getProperty<String>("parchment_version")
                    add("mappings", loomEx.layered {
                        officialMojangMappings()
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

            add("compileOnly", "com.google.code.findbugs:jsr305:3.0.2")
            add("testCompileOnly", "com.google.code.findbugs:jsr305:3.0.2")

            add("testImplementation", platform("org.junit:junit-bom:5.10.2"))
            add("testImplementation", "org.junit.jupiter:junit-jupiter")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        }

        project.tasks.apply {
            named("processResources", ProcessResources::class.java).configure {
                val properties = mapOf(
                    "version" to project.version
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
                useJUnit()
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
            try {
                val link2 = if (link.endsWith('/')) link else "$link/"
                val res = checkLink("${link2}element-list") || checkLink("${link2}package-list")
                if (!res) {
                    println("Skipping ($link) due to connection errors")
                }

                res
            } catch (e: Exception) {
                println("Skipping ($link) due to: ${e.stackTraceToString()}")
                false
            }
        }
    }

    private fun checkLink(link: String): Boolean {
        val url = URI(link).toURL()
        val huc = url.openConnection() as HttpURLConnection
        huc.instanceFollowRedirects = true
        huc.requestMethod = "HEAD"
        val responseCode = huc.responseCode
        return responseCode / 100 == 2
    }
}
