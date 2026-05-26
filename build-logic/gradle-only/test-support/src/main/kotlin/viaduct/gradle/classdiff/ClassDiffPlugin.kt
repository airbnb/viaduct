package viaduct.gradle.classdiff

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
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

        // Ensure default schema resources exist
        DefaultSchemaPlugin.ensureApplied(project)

        // configureEach fires immediately when each schemaDiff is created — no afterEvaluate needed.
        ext.schemaDiffs.configureEach {
            val ssName = ext.sourceSetName.get()
            DefaultSchemaPlugin.wireToSourceSet(project, ssName)

            val g = configureSchemaGenerationTasks(project, this, codegenClasspath)

            val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
            val classesTaskName = "${ssName}Classes"
            val compileKotlinTaskName = "compile${ssName.capitalize()}Kotlin"

            // 1) SCHEMA task (bytecode): add its classes dir to the *output* of the target source set
            //    This puts the produced .class files on both compile & runtime classpaths.
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

    private data class GenTasks(
        val schema: TaskProvider<ClassDiffSchemaTask>,
        val grt: TaskProvider<ClassDiffGRTKotlinTask>
    )

    private fun configureSchemaGenerationTasks(
        project: Project,
        schemaDiff: SchemaDiff,
        codegenClasspath: Configuration
    ): GenTasks {
        // Lazy FileCollection: defers resolveSchemaFiles() until task execution, by which
        // time the user's schemaDiff { ... } configure block has fully run.
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
    ): TaskProvider<ClassDiffSchemaTask> =
        project.tasks.register<ClassDiffSchemaTask>(
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
        }

    private fun configureGRTGeneration(
        project: Project,
        schemaDiff: SchemaDiff,
        schemaFiles: FileCollection,
        codegenClasspath: Configuration
    ): TaskProvider<ClassDiffGRTKotlinTask> =
        project.tasks.register<ClassDiffGRTKotlinTask>(
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
        }
}
