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
import net.neoforged.moddevgradle.dsl.NeoForgeExtension
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
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

abstract class SubmoduleExtension(
    private val project: Project, private val platform: Platform, private val submoduleMode: SubmoduleMode,
    private val xplatMode: XplatMode, private val modId: String, private val usingKotlin: Boolean
) {
    private val loom: Boolean
    private val moddev: Boolean

    init {
        if (submoduleMode == SubmoduleMode.ARCHITECTURY) {
            loom = true
            moddev = false
        } else {
            if (platform == Platform.NEOFORGE) {
                loom = false
                moddev = true
            } else if (platform == Platform.XPLAT) {
                if (xplatMode == XplatMode.MODDEV) {
                    loom = false
                    moddev = true
                } else {
                    loom = true
                    moddev = false
                }
            } else {
                loom = true
                moddev = false
            }
        }
    }

    private lateinit var xplatName: String
    private var refmapName: String = "none"
    private val transitiveProjectDependencies = mutableListOf<ProjectDep>()
    private val transitiveExternalDependencies = mutableListOf<ExternalDep>()

    var expansions = mapOf<String, Any>()
    var metadataFiles = setOf<String>()

    fun setLibsDirectory() {
        val baseEx = project.extensions.getByType(BasePluginExtension::class)
        baseEx.libsDirectory.set(project.rootProject.layout.buildDirectory.dir("libs"))
    }

    /**
     * Mixin expansions are also applied when applying an xplat connection.
     */
    fun applyMixinExpansions(refmapBasename: String) {
        refmapName = "${refmapBasename}.refmap.json"

        if (loom) {
            val loomEx = project.extensions.getByType(LoomGradleExtensionAPI::class)
            loomEx.mixin.defaultRefmapName.set(refmapName)
        }

        project.tasks.named("processResources", ProcessResources::class).configure {
            if (loom && platform != Platform.NEOFORGE) {
                val properties = mapOf("refmap" to refmapName) + expansions

                filesMatching("*.mixins.json") {
                    expand(properties)
                }

                inputs.properties(properties)
            } else {
                val properties = expansions

                exclude("fabric.mod.json")

                filesMatching("*.mixins.json") {
                    filter { if (it.contains("refmap")) "" else it }
                    expand(properties)
                }

                inputs.properties(properties)
            }
        }
    }

    fun applyXplatConnection(xplatName: String) {
        this.xplatName = xplatName

        val xplatProject = project.evaluationDependsOn(xplatName)

        val xplatSubmodule = xplatProject.extensions.getByType(SubmoduleExtension::class)
        val xplatSourceSets = xplatProject.extensions.getByType(SourceSetContainer::class)
        val mainSource = xplatSourceSets.named("main")

        if (loom) {
            val loomEx = project.extensions.getByType(LoomGradleExtensionAPI::class)
            val xplatLoom = xplatProject.extensions.getByType(LoomGradleExtensionAPI::class)

            if (loomEx.mods.findByName("main") != null) {
                loomEx.mods.named("main").configure { sourceSet(mainSource.get()) }
            } else if (loomEx.mods.findByName(modId) != null) {
                loomEx.mods.named(modId) {
                    sourceSet(project.extensions.getByType(SourceSetContainer::class).named("main").get())
                    sourceSet(mainSource.get())
                }
            }

            refmapName = xplatSubmodule.refmapName

            if (platform != Platform.NEOFORGE) {
                loomEx.mixin.defaultRefmapName.set(refmapName)
            }
        } else if (moddev) {
            val neoforgeEx = project.extensions.getByType<NeoForgeExtension>()
            neoforgeEx.mods.named(modId) {
                // this seems to be breaking mixins
//                sourceSet(mainSource.get())
            }
        }

        project.dependencies {
            add("compileOnly", project(xplatName, configuration = "namedElements")) {
                isTransitive = false
            }
        }

        for (transitiveDep in xplatSubmodule.transitiveProjectDependencies) {
            when (platform) {
                Platform.XPLAT -> throw UnsupportedOperationException(
                    "Cannot apply an xplat connection from an xplat project"
                )
                Platform.NEOFORGE -> neoforgeProjectDependency(
                    transitiveDep.projectBase, transitiveDep.api, transitiveDep.include
                )
                Platform.FABRIC -> fabricProjectDependency(
                    transitiveDep.projectBase, transitiveDep.api, transitiveDep.include
                )
                Platform.MOJMAP -> mojmapProjectDependency(transitiveDep.projectBase, transitiveDep.api)
            }
        }

        for (transitiveDep in xplatSubmodule.transitiveExternalDependencies) {
            when (platform) {
                Platform.XPLAT -> throw UnsupportedOperationException(
                    "Cannot apply an xplat connection from an xplat project"
                )
                Platform.NEOFORGE -> neoforgeExternalDependency(
                    transitiveDep.api, transitiveDep.include, transitiveDep.getter
                )
                Platform.FABRIC -> fabricExternalDependency(
                    transitiveDep.api, transitiveDep.include, transitiveDep.getter
                )
                Platform.MOJMAP -> mojmapExternalDependency(transitiveDep.api, transitiveDep.getter)
            }
        }

        project.tasks.apply {
            named("processResources", ProcessResources::class.java).configure {
                from(mainSource.map { it.resources })

                if (loom && platform != Platform.NEOFORGE) {
                    val properties = mapOf("refmap" to refmapName) + expansions

                    filesMatching("*.mixins.json") {
                        expand(properties)
                    }

                    inputs.properties(properties)
                } else {
                    val properties = expansions

                    exclude("fabric.mod.json")

                    filesMatching("*.mixins.json") {
                        filter { if (it.contains("refmap")) "" else it }
                        expand(properties)
                    }

                    inputs.properties(properties)
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
        val width = project.findProperty("minecraft_width") as? String
        val height = project.findProperty("minecraft_height") as? String
        val username = project.findProperty("minecraft_username") as? String
        val uuid = project.findProperty("minecraft_uuid") as? String

        val args = mutableListOf<String>()
        if (width != null && height != null) {
            args += listOf("--width", width, "--height", height)
        }
        if (username != null && uuid != null) {
            args += listOf("--username", username, "--uuid", uuid)
        }

        if (loom) {
            val loomEx = project.extensions.getByType(LoomGradleExtensionAPI::class)
            loomEx.runs {
                named("client") {
                    ideConfigGenerated(true)
                    programArgs(args)
                }
                named("server") {
                    ideConfigGenerated(true)
                }
            }
        } else if (moddev) {
            val neoforgeEx = project.extensions.getByType(NeoForgeExtension::class)
            neoforgeEx.runs {
                create("client") {
                    client()
                    programArguments.addAll(args)
                }
                create("server") {
                    server()
                    programArgument("--nogui")
                }
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

    fun xplatProjectDependency(
        projectBase: String, transitive: Boolean = true, api: Boolean = true, include: Boolean = true,
        addMods: Boolean = true
    ) {
        val config = if (api) "api" else "compileOnly"
        val xplatName = if (projectBase == ":") ":xplat" else "${projectBase}-xplat"

        project.dependencies {
            add(config, project(xplatName, configuration = "namedElements"))
        }

        if (transitive) {
            transitiveProjectDependencies.add(ProjectDep(projectBase, api, include, addMods))
        }
    }

    fun fabricProjectDependency(projectBase: String, api: Boolean = true, include: Boolean = true) {
        val config = if (api) "api" else "implementation"
        val xplatName = if (projectBase == ":") ":xplat" else "${projectBase}-xplat"
        val fabricName = if (projectBase == ":") ":fabric" else "${projectBase}-fabric"

        project.dependencies {
            add("compileOnly", project(xplatName, configuration = "namedElements")) {
                isTransitive = false
            }
            add(config, project(fabricName, configuration = "namedElements"))
            if (include) add("include", project(fabricName))
        }
    }

    fun neoforgeProjectDependency(
        projectBase: String, api: Boolean = true, include: Boolean = true, addMods: Boolean = true
    ) {
        val config = if (api) "api" else "implementation"
        val xplatName = if (projectBase == ":") ":xplat" else "${projectBase}-xplat"
        val neoforgeName = if (projectBase == ":") ":neoforge" else "${projectBase}-neoforge"
        // we assume all modules in a project have the same xplat mode
        val xplatConfiguration = if (xplatMode == XplatMode.LOOM) "namedElements" else null

        project.dependencies {
            if (moddev) {
                // assumes that xplat is a loom-project
                add("compileOnly", project(xplatName, configuration = xplatConfiguration)) {
                    isTransitive = false
                }
                add(config, project(neoforgeName))
                if (include) add("jarJar", project(neoforgeName))
            } else {
                add("compileOnly", project(xplatName, configuration = xplatConfiguration)) {
                    isTransitive = false
                }
                add(config, project(neoforgeName, configuration = "namedElements"))
                if (include) add("include", project(neoforgeName))
            }
        }

        if (addMods && moddev) {
            val neoforgeEx = project.extensions.getByType<NeoForgeExtension>()
            val depProject = project.evaluationDependsOn(neoforgeName)
            val depSubmodule = depProject.extensions.getByType<SubmoduleExtension>()
            val depSourceSet = depProject.extensions.getByType<SourceSetContainer>()

            neoforgeEx.mods.create(depSubmodule.modId) {
                sourceSet(depSourceSet.named("main").get())
            }
        }
    }

    fun mojmapProjectDependency(projectBase: String, api: Boolean = true) {
        val config = if (api) "api" else "compileOnly"
        val mojmapName = if (projectBase == ":") ":xplat-mojmap" else "${projectBase}-xplat-mojmap"

        project.dependencies {
            add(config, project(mojmapName, configuration = "namedElements"))
        }
    }

    fun xplatExternalDependency(
        transitive: Boolean = true, api: Boolean = true, include: Boolean = true, getter: (platform: String) -> String
    ) {
        val config = if (api) "modApi" else "modCompileOnly"
        val xplatPlatform = if (xplatMode == XplatMode.LOOM) "xplat-intermediary" else "xplat-mojmap"

        project.dependencies {
            add(config, getter(xplatPlatform))
        }

        if (transitive) {
            transitiveExternalDependencies.add(ExternalDep(getter, api, include))
        }
    }

    fun fabricExternalDependency(api: Boolean = true, include: Boolean = true, getter: (platform: String) -> String) {
        val config = if (api) "modApi" else "modImplementation"

        project.dependencies {
            add(config, getter("fabric"))
            if (include) add("include", getter("fabric"))
        }
    }

    fun neoforgeExternalDependency(api: Boolean = true, include: Boolean = true, getter: (platform: String) -> String) {
        project.dependencies {
            if (platform == Platform.NEOFORGE && submoduleMode == SubmoduleMode.PLATFORM) {
                val config = if (api) "api" else "implementation"
                add(config, getter("neoforge"))
                if (include) add("jarJar", getter("neoforge"))
            } else {
                val config = if (api) "modApi" else "modImplementation"
                add(config, getter("neoforge"))
                if (include) add("include", getter("neoforge"))
            }
        }
    }

    fun mojmapExternalDependency(api: Boolean = true, getter: (platform: String) -> String) {
        val config = if (api) "modApi" else "modCompileOnly"

        project.dependencies {
            add(config, getter("xplat-mojmap"))
        }
    }
}
