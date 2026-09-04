package viaduct.gradle.classdiff

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskOutputs
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import viaduct.gradle.common.GenerateArbitrarySchemaTask
import viaduct.gradle.common.getOrCreateCodegenClasspath
import viaduct.gradle.defaultschema.DefaultSchemaPlugin
import viaduct.gradle.shared.BuildFlags
import viaduct.gradle.utils.capitalize

abstract class ClassDiffPlugin : Plugin<Project> {
    companion object {
        private const val PLUGIN_GROUP = "viaduct-classdiff"
        private const val GENERATED_SOURCES_PATH = "generated-sources/classdiff"
    }

    override fun apply(project: Project) {
        val ext = project.extensions.create<ClassDiffExtension>("viaductClassDiff", project)

        val codegenClasspath = project.getOrCreateCodegenClasspath()

        DefaultSchemaPlugin.ensureApplied(project)

        // configureEach's action runs before the schemaDiff { ... } configure block itself, so
        // SchemaDiff properties read here must be lazy (Property bindings), not eager isPresent checks.
        ext.schemaDiffs.configureEach {
            val schemaDiff = this
            val ssName = ext.sourceSetName.get()
            DefaultSchemaPlugin.wireToSourceSet(project, ssName)

            val generatedSchemaTask = configureGeneratedSchemaResource(project, schemaDiff, ssName, codegenClasspath)

            val g = configureSchemaGenerationTasks(project, schemaDiff, codegenClasspath)
            g.schema.configure { dependsOn(generatedSchemaTask) }

            val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
            val classesTaskName = "${ssName}Classes"
            val compileKotlinTaskName = "compile${ssName.capitalize()}Kotlin"

            javaExt.sourceSets.named(ssName).configure {
                output.dir(mapOf("builtBy" to g.schema), g.schema.flatMap { it.generatedSrcDir })
                java.srcDir(g.grt.flatMap { it.generatedSrcDir })
            }
            project.tasks.named(classesTaskName).configure { dependsOn(g.schema) }

            project.plugins.withId("org.jetbrains.kotlin.jvm") {
                project.tasks.named(compileKotlinTaskName).configure { dependsOn(g.schema) }
                val kext = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
                kext.sourceSets.named(ssName).configure {
                    kotlin.srcDir(g.grt.flatMap { it.generatedSrcDir })
                }
            }
        }
    }

    /** Always registered; `onlyIf` no-ops it at execution time if [schemaDiff] has no generated resource. */
    private fun configureGeneratedSchemaResource(
        project: Project,
        schemaDiff: SchemaDiff,
        sourceSetName: String,
        codegenClasspath: Configuration
    ): TaskProvider<GenerateArbitrarySchemaTask> {
        val resourcesDir = project.layout.buildDirectory.dir(
            "generated-resources/classdiff-${schemaDiff.name}-arbitrary-schema"
        )
        // Capturing schemaDiff (not just this Property) below would pull in its non-serializable Project.
        val resourcePath: Property<String> = schemaDiff.generatedSchemaResourcePath
        val task = project.tasks.register<GenerateArbitrarySchemaTask>(
            "generateSchemaDiff${schemaDiff.name.capitalize()}ArbitrarySchema"
        ) {
            group = PLUGIN_GROUP
            description = "Generates a random, extensive GraphQL schema fragment for schema diff '${schemaDiff.name}'"
            this.codegenClasspath.from(codegenClasspath)
            outputFile.set(resourcePath.flatMap { resourcesDir.map { dir -> dir.file(it) } })
            onlyIf { resourcePath.isPresent }
        }

        DefaultSchemaPlugin.wireGeneratedResourcesIntoSourceSet(project, sourceSetName, resourcesDir, task)

        return task
    }

    /** A generated schema is re-randomized every build, so caching it is always a miss. */
    private fun TaskOutputs.cacheIfNotGenerated(generatedResourcePath: Property<String>) {
        cacheIf("schema is not regenerated with a random seed every build") { !generatedResourcePath.isPresent }
    }

    private data class GenTasks(
        val schema: TaskProvider<ClassDiffSchemaTask>,
        val grt: TaskProvider<ClassDiffGRTKotlinTask>
    )

    private fun configureSchemaGenerationTasks(
        project: Project,
        schemaDiff: SchemaDiff,
        codegenClasspath: Configuration
    ): GenTasks {
        val schemaFiles = project.files(project.provider { schemaDiff.resolveSchemaFiles() })

        val schemaTask = configureSchemaGeneration(project, schemaDiff, schemaFiles, codegenClasspath)
        val grtTask = configureGRTGeneration(project, schemaDiff, schemaFiles, codegenClasspath)
        grtTask.configure { dependsOn(schemaTask) }

        return GenTasks(schema = schemaTask, grt = grtTask)
    }

    private fun configureSchemaGeneration(
        project: Project,
        schemaDiff: SchemaDiff,
        schemaFiles: FileCollection,
        codegenClasspath: Configuration
    ): TaskProvider<ClassDiffSchemaTask> {
        val generatedResourcePath: Property<String> = schemaDiff.generatedSchemaResourcePath
        return project.tasks.register<ClassDiffSchemaTask>(
            "generateSchemaDiff${schemaDiff.name.capitalize()}SchemaObjects"
        ) {
            group = PLUGIN_GROUP
            description = "Generates schema objects for schema diff '${schemaDiff.name}'"
            schemaName.set("default")
            packageName.set(schemaDiff.actualPackage)
            buildFlags.putAll(BuildFlags.DEFAULT)
            workerNumber.set(0)
            workerCount.set(1)
            includeIneligibleForTesting.set(true)
            this.schemaFiles.from(schemaFiles)
            this.defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.codegenClasspath.from(codegenClasspath)
            generatedSrcDir.set(project.layout.buildDirectory.dir(GENERATED_SOURCES_PATH))
            dependsOn(project.tasks.named("processResources"))
            doFirst { generatedSrcDir.get().asFile.mkdirs() }
            outputs.cacheIfNotGenerated(generatedResourcePath)
        }
    }

    private fun configureGRTGeneration(
        project: Project,
        schemaDiff: SchemaDiff,
        schemaFiles: FileCollection,
        codegenClasspath: Configuration
    ): TaskProvider<ClassDiffGRTKotlinTask> {
        val generatedResourcePath: Property<String> = schemaDiff.generatedSchemaResourcePath
        return project.tasks.register<ClassDiffGRTKotlinTask>(
            "generateSchemaDiff${schemaDiff.name.capitalize()}KotlinGrts"
        ) {
            group = PLUGIN_GROUP
            description = "Generates Kotlin GRTs for schema diff '${schemaDiff.name}'"
            this.schemaFiles.from(schemaFiles)
            this.defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.codegenClasspath.from(codegenClasspath)
            packageName.set(schemaDiff.expectedPackage)
            buildFlags.putAll(BuildFlags.DEFAULT)
            generatedSrcDir.set(
                schemaDiff.expectedPackage.flatMap { pkg ->
                    project.layout.buildDirectory.dir("$GENERATED_SOURCES_PATH/${pkg.replace('.', '/')}")
                }
            )
            dependsOn(project.tasks.named("processResources"))
            doFirst { generatedSrcDir.get().asFile.mkdirs() }
            outputs.cacheIfNotGenerated(generatedResourcePath)
        }
    }
}
