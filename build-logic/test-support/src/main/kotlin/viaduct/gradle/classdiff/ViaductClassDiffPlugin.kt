package viaduct.gradle.classdiff

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import viaduct.gradle.common.getOrCreateCodegenClasspath
import viaduct.gradle.defaultschema.DefaultSchemaPlugin
import viaduct.gradle.shared.BuildFlags
import viaduct.gradle.utils.capitalize

abstract class ViaductClassDiffPlugin : Plugin<Project> {
    companion object {
        private const val PLUGIN_GROUP = "viaduct-classdiff"
        private const val GENERATED_SOURCES_PATH = "generated-sources/classdiff"
    }

    override fun apply(project: Project) {
        val ext = project.extensions.create<ViaductClassDiffExtension>("viaductClassDiff", project)

        val codegenClasspath = project.getOrCreateCodegenClasspath()

        // Ensure default schema resources exist
        DefaultSchemaPlugin.ensureApplied(project)

        project.afterEvaluate {
            val diffs = ext.schemaDiffs.get()
            if (diffs.isEmpty()) {
                project.logger.info("No schema diffs configured")
                return@afterEvaluate
            }

            val ssName = ext.sourceSetName.get()
            DefaultSchemaPlugin.wireToSourceSet(project, ssName)

            val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)

            val gens: List<GenTasks> = diffs.mapNotNull { configureSchemaGenerationTasks(project, it, codegenClasspath) }

            val targetJavaSS = javaExt.sourceSets.getByName(ssName)

            // If Kotlin plugin is applied, we’ll add the GRT sources to the target Kotlin source set
            var addToKotlinTarget: ((Any) -> Unit)? = null
            project.plugins.withId("org.jetbrains.kotlin.jvm") {
                val kext = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
                val targetK = kext.sourceSets.getByName(ssName)
                addToKotlinTarget = { provider -> targetK.kotlin.srcDir(provider) }
            }

            // Derive task names from source set name
            val classesTaskName = "${ssName}Classes"
            val compileKotlinTaskName = "compile${ssName.capitalize()}Kotlin"

            gens.forEach { g ->
                // 1) SCHEMA task (bytecode): add its classes dir to the *output* of the target source set
                //    This puts the produced .class files on both compile & runtime classpaths.
                targetJavaSS.output.dir(
                    mapOf("builtBy" to g.schema),
                    g.schema.flatMap { it.generatedSrcDir }
                )

                // Also ensure the usual aggregators depend on it (no circular edges here).
                project.tasks.named(classesTaskName).configure { dependsOn(g.schema) }
                project.plugins.withId("org.jetbrains.kotlin.jvm") {
                    project.tasks.named(compileKotlinTaskName).configure { dependsOn(g.schema) }
                }

                // 2) GRT task (Kotlin sources): add as sources to target (both Kotlin + Java for IDEs)
                addToKotlinTarget?.invoke(g.grt.flatMap { it.generatedSrcDir })
                targetJavaSS.java.srcDir(g.grt.flatMap { it.generatedSrcDir })
            }
        }
    }

    private data class GenTasks(
        val schema: TaskProvider<ViaductClassDiffSchemaTask>,
        val grt: TaskProvider<ViaductClassDiffGRTKotlinTask>
    )

    private fun configureSchemaGenerationTasks(
        project: Project,
        schemaDiff: SchemaDiff,
        codegenClasspath: Configuration
    ): GenTasks? {
        val schemaFiles = schemaDiff.resolveSchemaFiles()
        if (schemaFiles.isEmpty()) {
            project.logger.error("No valid schema files found for schema diff '${schemaDiff.name}'")
            return null
        }

        val schemaTask = configureSchemaGeneration(project, schemaDiff, schemaFiles, codegenClasspath)
        val grtTask = configureGRTGeneration(project, schemaDiff, schemaFiles, codegenClasspath)
        grtTask.configure { dependsOn(schemaTask) }

        return GenTasks(schema = schemaTask, grt = grtTask)
    }

    private fun configureSchemaGeneration(
        project: Project,
        schemaDiff: SchemaDiff,
        schemaFiles: List<File>,
        codegenClasspath: Configuration
    ): TaskProvider<ViaductClassDiffSchemaTask> =
        project.tasks.register<ViaductClassDiffSchemaTask>(
            "generateSchemaDiff${schemaDiff.name.capitalize()}SchemaObjects"
        ) {
            group = PLUGIN_GROUP
            description = "Generates schema objects for schema diff '${schemaDiff.name}'"
            schemaName.set("default")
            packageName.set(schemaDiff.actualPackage.get())
            buildFlags.putAll(BuildFlags.DEFAULT)
            workerNumber.set(0)
            workerCount.set(1)
            includeIneligibleForTesting.set(true)
            this.schemaFiles.from(schemaFiles)
            this.codegenClasspath.from(codegenClasspath)
            generatedSrcDir.set(project.layout.buildDirectory.dir(GENERATED_SOURCES_PATH))
            dependsOn("processResources")
            doFirst { generatedSrcDir.get().asFile.mkdirs() }
        }

    private fun configureGRTGeneration(
        project: Project,
        schemaDiff: SchemaDiff,
        schemaFiles: List<File>,
        codegenClasspath: Configuration
    ): TaskProvider<ViaductClassDiffGRTKotlinTask> {
        val pkg = schemaDiff.expectedPackage.get()
        val pkgPath = pkg.replace(".", "/")

        return project.tasks.register<ViaductClassDiffGRTKotlinTask>(
            "generateSchemaDiff${schemaDiff.name.capitalize()}KotlinGrts"
        ) {
            group = PLUGIN_GROUP
            description = "Generates Kotlin GRTs for schema diff '${schemaDiff.name}'"
            this.schemaFiles.from(schemaFiles)
            this.codegenClasspath.from(codegenClasspath)
            packageName.set(pkg)
            buildFlags.putAll(BuildFlags.DEFAULT)
            generatedSrcDir.set(project.layout.buildDirectory.dir("$GENERATED_SOURCES_PATH/$pkgPath"))
            dependsOn("processResources")
            doFirst { generatedSrcDir.get().asFile.mkdirs() }
        }
    }
}
