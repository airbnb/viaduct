package viaduct.gradle

import centralSchemaDirectory
import grtClassesDirectory
import dslClassesDirectory
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.register
import viaduct.gradle.ViaductPluginCommon.addViaductDependencies
import viaduct.gradle.ViaductPluginCommon.addViaductTestDependencies
import viaduct.gradle.ViaductPluginCommon.addViaductTestFixtures
import viaduct.gradle.ViaductPluginCommon.applyViaductBOM
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.GenerateDslFilesTask
import viaduct.gradle.task.GenerateGRTClassFilesTask

class ViaductApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            require(this == rootProject) {
                "Apply 'com.airbnb.viaduct.application-gradle-plugin' only to the root project."
            }

            val appExt = extensions.create("viaductApplication", ViaductApplicationExtension::class.java, objects)

            // Set default BOM version to plugin version
            appExt.bomVersion.convention(ViaductPluginCommon.BOM.getDefaultVersion())

            val assembleCentralSchemaTask = setupAssembleCentralSchemaTask()
            setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask)

            val generateGRTsTask = setupGenerateGRTsTask(appExt, assembleCentralSchemaTask)

            // DSL generation - only setup if enabled
            val generateDslTask = setupGenerateDslTask(appExt, assembleCentralSchemaTask)

            plugins.withId("java") {
                if (appExt.applyBOM.get()) {
                    applyViaductBOM(appExt.bomVersion.get())
                    addViaductDependencies(appExt.viaductDependencies.get())
                    addViaductTestDependencies(appExt.viaductTestDependencies.get())
                    addViaductTestFixtures(appExt.viaductTestFixtures.get())
                }

                // Add DSL sources to compilation only if enabled
                afterEvaluate {
                    if (appExt.enableDsl.get()) {
                        addDslSourcesToCompilation(generateDslTask)
                    }
                }
            }

            configureIdeaIntegration(generateGRTsTask)
            setupConsumableConfigurationForGRT(generateGRTsTask.flatMap { it.archiveFile })

            // Setup DSL configuration (always setup but only populated when enabled)
            setupConsumableConfigurationForDsl(appExt, generateDslTask)

            this.dependencies.add("api", files(generateGRTsTask.flatMap { it.archiveFile }))
        }

    private fun Project.setupAssembleCentralSchemaTask(): TaskProvider<AssembleCentralSchemaTask> {
        val allPartitions = configurations.create(ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING).apply {
            description = "Resolvable configuration where all viaduct-module plugins send their schema partitions."
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION) }
        }

        val assembleCentralSchemaTask = tasks.register<AssembleCentralSchemaTask>("assembleViaductCentralSchema") {
            schemaPartitions.setFrom(allPartitions.incoming.artifactView {}.files)

            val baseSchemaDir = project.file("src/main/viaduct/schemabase")
            if (baseSchemaDir.exists()) {
                baseSchemaFiles.setFrom(
                    project.fileTree(baseSchemaDir) {
                        include("**/*.graphqls")
                    }
                )
            }

            outputDirectory.set(centralSchemaDirectory())
        }

        return assembleCentralSchemaTask
    }

    /** Call the bytecode-generator to generate GRT files. */
    private fun Project.setupGenerateGRTsTask(
        appExt: ViaductApplicationExtension,
        assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>,
    ): TaskProvider<Jar> {
        val pluginClasspath = files(ViaductPluginCommon.getClassPathElements(this@ViaductApplicationPlugin::class.java))

        val generateGRTClassesTask = tasks.register<GenerateGRTClassFilesTask>("generateViaductGRTClassFiles") {
            grtClassesDirectory.set(grtClassesDirectory())
            schemaFiles.setFrom(assembleCentralSchemaTask.flatMap { it.outputDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") }.files } })
            grtPackageName.set(appExt.grtPackageName)
            classpath.setFrom(pluginClasspath)
            mainClass.set(CODEGEN_MAIN_CLASS)
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductGRTs") {
            group = "viaduct"
            description = "Package GRT class files with the central schema."

            archiveBaseName.set("viaduct-grt")
            includeEmptyDirs = false

            from(generateGRTClassesTask.flatMap { it.grtClassesDirectory })

            from(assembleCentralSchemaTask.flatMap { it.outputDirectory }) {
                into("viaduct/centralSchema")
                exclude(BUILTIN_SCHEMA_FILE)
                includeEmptyDirs = false
            }
        }

        return generateGRTsTask
    }

    /** Generate DSL query builder files (experimental). */
    private fun Project.setupGenerateDslTask(
        appExt: ViaductApplicationExtension,
        assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>,
    ): TaskProvider<GenerateDslFilesTask> {
        val pluginClasspath = files(ViaductPluginCommon.getClassPathElements(this@ViaductApplicationPlugin::class.java))

        val generateDslTask = tasks.register<GenerateDslFilesTask>("generateViaductDsl") {
            dslOutputDirectory.set(dslClassesDirectory())
            schemaFiles.setFrom(assembleCentralSchemaTask.flatMap {
                it.outputDirectory.map { dir ->
                    dir.asFileTree.matching { include("**/*.graphqls") }.files
                }
            })
            dslPackageName.set(appExt.dslPackageName)
            classpath.setFrom(pluginClasspath)
            mainClass.set(DSL_CODEGEN_MAIN_CLASS)
            onlyIf { appExt.enableDsl.get() }
        }

        return generateDslTask
    }

    /** NEW: Add generated DSL sources to Kotlin compilation. */
    private fun Project.addDslSourcesToCompilation(generateDslTask: TaskProvider<GenerateDslFilesTask>) {
        plugins.withId("org.jetbrains.kotlin.jvm") {
            val kotlinSourceSet = extensions.findByName("kotlin") as? org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
            kotlinSourceSet?.sourceSets?.named("main")?.configure {
                kotlin.srcDir(generateDslTask.flatMap { it.dslOutputDirectory })
            }

            // Make sure compilation depends on DSL generation
            tasks.named("compileKotlin").configure {
                dependsOn(generateDslTask)
            }
        }
    }

    private fun Project.setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>) {
        configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING).apply {
            description = """
              Consumable configuration consisting of a directory containing all schema fragments.  This directory
              is organized as a top-level file named $BUILTIN_SCHEMA_FILE, plus directories named "parition[/module-name]/graphql",
              where module-name is the modulePackageSuffix of the module with dots replaced by slashes (this segment is
              not present if the suffix is blank).
            """.trimIndent()
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.CENTRAL_SCHEMA) }
            outgoing.artifact(assembleCentralSchemaTask)
        }
    }

    private fun Project.setupConsumableConfigurationForGRT(artifact: Provider<RegularFile>) {
        configurations.create(ViaductPluginCommon.Configs.GRT_CLASSES_OUTGOING).apply {
            description =
                "Consumable configuration for the jar file containing the GRT classes plus the central schema's graphqls file."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.GRT_CLASSES)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR)
                )
            }
            outgoing.artifact(artifact)
        }
    }

    /** Setup consumable configuration for DSL classes (only active when enableDsl is true). */
    private fun Project.setupConsumableConfigurationForDsl(
        appExt: ViaductApplicationExtension,
        generateDslTask: TaskProvider<GenerateDslFilesTask>
    ) {
        configurations.create(ViaductPluginCommon.Configs.DSL_CLASSES_OUTGOING).apply {
            description = "Consumable configuration for generated DSL query builder classes (experimental)."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.DSL_CLASSES)
            }
            // Only add artifact if DSL is enabled
            afterEvaluate {
                if (appExt.enableDsl.get()) {
                    outgoing.artifact(generateDslTask.flatMap { it.dslOutputDirectory })
                }
            }
        }
    }

    companion object {
        private const val CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.SchemaObjectsBytecode\$Main"
        private const val DSL_CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.KotlinDslGenerator\$Main"
        const val BUILTIN_SCHEMA_FILE = "BUILTIN_SCHEMA.graphqls"
    }
}
