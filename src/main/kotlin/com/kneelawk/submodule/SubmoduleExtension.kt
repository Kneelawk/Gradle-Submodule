/*
 * MIT License
 *
 * Copyright (c) 2024 Kneelawk.
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
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

abstract class SubmoduleExtension(private val project: Project, private val javaVersion: String) {
    private var usingKotlin = false
    private lateinit var xplatName: String
    private val transitiveProjectDependencies = mutableListOf<ProjectDep>()
    private val transitiveExternalDependencies = mutableListOf<ExternalDep>()

    fun applyKotlin(platform: String) {
        usingKotlin = true
        project.plugins.apply("org.jetbrains.kotlin.jvm")

        project.tasks.withType<KotlinCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion))
        }

        project.dependencies {
            when (platform) {
                "xplat", "mojmap" -> {
                    add("compileOnly", "org.jetbrains.kotlin:kotlin-stdlib")
                    add("compileOnly", "org.jetbrains.kotlin:kotlin-reflect")
                    add("testCompileOnly", "org.jetbrains.kotlin:kotlin-stdlib")
                    add("testCompileOnly", "org.jetbrains.kotlin:kotlin-reflect")
                }
                "neoforge" -> {
                    val kotlinVersion = project.getProperty<String>("neoforge_kotlin_version")
                    add("modCompileOnly", "thedarkcolour:kotlinforforge-neoforge:$kotlinVersion")
                    add("modLocalRuntime", "thedarkcolour:kotlinforforge-neoforge:$kotlinVersion")
                }
                "fabric" -> {
                    val kotlinVersion = project.getProperty<String>("fabric_kotlin_version")
                    add("modCompileOnly", "net.fabricmc:fabric-language-kotlin:$kotlinVersion")
                    add("modLocalRuntime", "net.fabricmc:fabric-language-kotlin:$kotlinVersion")
                }
            }
        }
    }

    fun setLibsDirectory() {
        val baseEx = project.extensions.getByType(BasePluginExtension::class)
        baseEx.libsDirectory.set(project.rootProject.layout.buildDirectory.dir("libs"))
    }

    fun setRefmaps(basename: String) {
        val refmapName = "${basename}.refmap.json"

        val loomEx = project.extensions.getByType(LoomGradleExtensionAPI::class)
        loomEx.mixin.defaultRefmapName.set(refmapName)

        project.tasks.named("processResources", ProcessResources::class).configure {
            filesMatching("*.mixins.json") {
                expand(mapOf("refmap" to refmapName))
            }

            inputs.property("refmap", refmapName)
        }
    }

    fun applyNeoforgeDependency() {
        project.repositories {
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

        project.dependencies.apply {
            val neoforgeVersion = project.getProperty<String>("neoforge_version")
            add("neoForge", "net.neoforged:neoforge:$neoforgeVersion")
        }
    }

    fun applyFabricLoaderDependency() {
        project.dependencies.apply {
            val fabricLoaderVersion = project.getProperty<String>("fabric_loader_version")
            add("modCompileOnly", "net.fabricmc:fabric-loader:$fabricLoaderVersion")
            add("modLocalRuntime", "net.fabricmc:fabric-loader:$fabricLoaderVersion")
        }
    }

    fun applyFabricApiDependency() {
        project.dependencies.apply {
            val fapiVersion = project.getProperty<String>("fapi_version")
            add("modCompileOnly", "net.fabricmc.fabric-api:fabric-api:$fapiVersion")
            add("modLocalRuntime", "net.fabricmc.fabric-api:fabric-api:$fapiVersion")
        }
    }

    fun applyXplatConnection(xplatName: String, platform: String) {
        this.xplatName = xplatName

        val xplatProject = project.evaluationDependsOn(xplatName)

        val loomEx = project.extensions.getByType(LoomGradleExtensionAPI::class)
        val xplatLoom = xplatProject.extensions.getByType(LoomGradleExtensionAPI::class)
        val xplatSubmodule = xplatProject.extensions.getByType(SubmoduleExtension::class)
        val xplatSourceSets = xplatProject.extensions.getByType(SourceSetContainer::class)
        val mainSource = xplatSourceSets.named("main")

        if (loomEx.mods.findByName("main") != null) {
            loomEx.mods.named("main").configure { sourceSet(mainSource.get()) }
        } else {
            loomEx.mods.create("main") {
                sourceSet(project.extensions.getByType(SourceSetContainer::class).named("main").get())
                sourceSet(mainSource.get())
            }
        }

        val onNeoForge = platform == "neoforge"

        if (!onNeoForge) {
            loomEx.mixin.defaultRefmapName.set(xplatLoom.mixin.defaultRefmapName)
        }

        project.dependencies.apply {
            add("compileOnly", project(xplatName, configuration = "namedElements"))
        }

        for (transitiveDep in xplatSubmodule.transitiveProjectDependencies) {
            when (platform) {
                "neoforge" -> neoforgeProjectDependency(transitiveDep.projectBase, transitiveDep.api)
                "fabric" -> fabricProjectDependency(transitiveDep.projectBase, transitiveDep.api)
                "mojmap" -> mojmapProjectDependency(transitiveDep.projectBase, transitiveDep.api)
            }
        }

        for (transitiveDep in xplatSubmodule.transitiveExternalDependencies) {
            when (platform) {
                "neoforge" -> neoforgeExternalDependency(transitiveDep.api, transitiveDep.getter)
                "fabric" -> fabricExternalDependency(transitiveDep.api, transitiveDep.getter)
                "mojmap" -> mojmapExternalDependency(transitiveDep.api, transitiveDep.getter)
            }
        }

        project.tasks.apply {
            named("processResources", ProcessResources::class.java).configure {
                from(mainSource.map { it.resources })

                if (onNeoForge) {
                    exclude("fabric.mod.json")

                    filesMatching("*.mixins.json") {
                        filter { if (it.contains("refmap")) "" else it }
                    }
                } else {
                    val refmapName = loomEx.mixin.defaultRefmapName.get()

                    filesMatching("*.mixins.json") {
                        expand(mapOf("refmap" to refmapName))
                    }

                    inputs.property("refmap", refmapName)
                }
            }

            withType<JavaCompile>().configureEach {
                source(xplatSourceSets.named("main").map { it.allJava })
            }

            named("sourcesJar", Jar::class.java).configure {
                from(mainSource.map { it.allSource })
            }

            named("javadoc", Javadoc::class.java).configure {
                source(mainSource.map { it.allJava })
            }

            if (usingKotlin) {
                withType<KotlinCompile>().configureEach {
                    source(xplatSourceSets.named("main").map { it.extensions.getByName("kotlin") })
                }
            }
        }
    }

    fun generateRuns() {
        val username = project.findProperty("minecraft_username") as? String ?: "kneelawk"
        val uuid = project.findProperty("minecraft_uuid") as? String ?: "4c63e52938bd4ed5a14d77abbbe11aae"

        val loomEx = project.extensions.getByType(LoomGradleExtensionAPI::class)
        loomEx.runs {
            named("client") {
                ideConfigGenerated(true)
                programArgs("--width", "1280", "--height", "720", "--username", username, "--uuid", uuid)
            }
            named("server") {
                ideConfigGenerated(true)
            }
        }
    }

    fun forceRemap() {
        project.tasks.named("jar", Jar::class).configure {
            manifest {
                attributes("Fabric-Loom-Remap" to true)
            }
        }
    }

    fun disableRemap() {
        project.tasks.apply {
            named("remapJar", RemapJarTask::class).configure {
                targetNamespace.set("named")
            }
            named("remapSourcesJar", RemapSourcesJarTask::class).configure {
                targetNamespace.set("named")
            }
        }
    }

    fun setupJavadoc(wError: Boolean = true) {
        val javaEx = project.extensions.getByType(JavaPluginExtension::class)

        val packageName = project.findProperty("javadoc_package_name") as? String
        val javadocWerror = (project.findProperty("javadoc_werror") as? String)?.toBoolean() ?: true

        javaEx.withJavadocJar()

        project.tasks.named("javadoc", Javadoc::class).configure {
            (options as? StandardJavadocDocletOptions)?.apply {
                if (wError && javadocWerror) {
                    addBooleanOption("Werror", true)
                }
            }

            val javadocOptionsFile = project.rootProject.file("javadoc-options.txt")
            if (javadocOptionsFile.exists()) {
                options.optionFiles(javadocOptionsFile)
            }

            if (packageName != null) {
                exclude("$packageName/impl")
                exclude("$packageName/**/impl")
            }
        }
    }

    fun createDevExport() {
        project.configurations.apply {
            create("dev") {
                isCanBeConsumed = true
                isCanBeResolved = false
            }
        }

        val jarExt = project.tasks.run {
            create("jarExt", Jar::class.java) {
                from(named("compileJava"))
                from(named("processResources"))
                from(project.rootProject.file("LICENSE")) {
                    rename { "${it}_${project.rootProject.name}" }
                }
                archiveClassifier.set("jarExt")
                destinationDirectory.set(project.layout.buildDirectory.dir("devlibs"))
            }
        }

        project.artifacts.add("dev", jarExt)

        project.tasks.named("assemble").configure { dependsOn(jarExt) }
    }

    fun xplatProjectDependency(projectBase: String, transitive: Boolean = true, api: Boolean = true) {
        val config = if (api) "api" else "compileOnly"
        val xplatName = if (projectBase == ":") ":xplat" else "${projectBase}-xplat"

        project.dependencies {
            add(config, project(xplatName, configuration = "namedElements"))
            add("testCompileOnly", project(xplatName, configuration = "namedElements"))
            add("testRuntimeOnly", project(xplatName, configuration = "namedElements"))
        }

        if (transitive) {
            transitiveProjectDependencies.add(ProjectDep(projectBase, api))
        }
    }

    fun fabricProjectDependency(projectBase: String, api: Boolean = true) {
        val config = if (api) "api" else "implementation"
        val xplatName = if (projectBase == ":") ":xplat" else "${projectBase}-xplat"
        val fabricName = if (projectBase == ":") ":fabric" else "${projectBase}-fabric"

        project.dependencies {
            add("compileOnly", project(xplatName, configuration = "namedElements"))
            add(config, project(fabricName, configuration = "namedElements"))
            add("include", project(fabricName))
            add("testCompileOnly", project(xplatName, configuration = "namedElements"))
            add("testImplementation", project(fabricName, configuration = "namedElements"))
        }
    }

    fun neoforgeProjectDependency(projectBase: String, api: Boolean = true) {
        val config = if (api) "api" else "implementation"
        val xplatName = if (projectBase == ":") ":xplat" else "${projectBase}-xplat"
        val neoforgeName = if (projectBase == ":") ":neoforge" else "${projectBase}-neoforge"

        project.dependencies {
            add("compileOnly", project(xplatName, configuration = "namedElements"))
            add(config, project(neoforgeName, configuration = "namedElements"))
//            add("runtimeOnly", project("${projectBase}-neoforge", configuration = "dev"))
            add("include", project(neoforgeName))
            add("testCompileOnly", project(xplatName, configuration = "namedElements"))
            add("testCompileOnly", project(neoforgeName, configuration = "namedElements"))
            add("testRuntimeOnly", project(neoforgeName, configuration = "dev"))
        }
    }

    fun mojmapProjectDependency(projectBase: String, api: Boolean = true) {
        val config = if (api) "api" else "compileOnly"
        val mojmapName = if (projectBase == ":") ":xplat-mojmap" else "${projectBase}-xplat-mojmap"

        project.dependencies {
            add(config, project(mojmapName, configuration = "namedElements"))
            add("testCompileOnly", project(mojmapName, configuration = "namedElements"))
        }
    }

    fun xplatExternalDependency(transitive: Boolean = true, api: Boolean = true, getter: (platform: String) -> String) {
        val config = if (api) "modApi" else "modCompileOnly"

        project.dependencies {
            add(config, getter("xplat-intermediary"))
//            add("testModCompileOnly", getter("xplat-intermediary"))
//            add("testModLocalRuntime", getter("xplat-intermediary"))
        }

        if (transitive) {
            transitiveExternalDependencies.add(ExternalDep(getter, api))
        }
    }

    fun fabricExternalDependency(api: Boolean = true, getter: (platform: String) -> String) {
        val config = if (api) "modApi" else "modImplementation"

        project.dependencies {
            add(config, getter("fabric"))
            add("include", getter("fabric"))
//            add("testModImplementation", getter("fabric"))
        }
    }

    fun neoforgeExternalDependency(api: Boolean = true, getter: (platform: String) -> String) {
        val config = if (api) "modApi" else "modImplementation"

        project.dependencies {
            add(config, getter("neoforge"))
            add("include", getter("neoforge"))
//            add("testModImplementation", getter("neoforge"))
        }
    }

    fun mojmapExternalDependency(api: Boolean = true, getter: (platform: String) -> String) {
        val config = if (api) "modApi" else "modCompileOnly"

        project.dependencies {
            add(config, getter("xplat-mojmap"))
//            add("testModCompileOnly", getter("xplat-mojmap"))
        }
    }
}
