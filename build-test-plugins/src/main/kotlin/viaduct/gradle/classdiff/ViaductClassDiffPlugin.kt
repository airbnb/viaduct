package viaduct.gradle.classdiff

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import viaduct.gradle.ViaductPluginCommon
import viaduct.gradle.defaultschema.DefaultSchemaPlugin
import viaduct.gradle.utils.capitalize

abstract class ViaductClassDiffPlugin : Plugin<Project> {
    companion object {
        private const val PLUGIN_GROUP = "viaduct-classdiff"
        private const val GENERATED_SOURCES_PATH = "generated-sources/classdiff"
    }

    override fun apply(project: Project) {
        val ext = project.extensions.create<ViaductClassDiffExtension>("viaductClassDiff", project)

        // Ensure default schema resources exist
        DefaultSchemaPlugin.ensureApplied(project)

        project.afterEvaluate {
            val diffs = ext.schemaDiffs.get()
            if (diffs.isEmpty()) {
                project.logger.info("No schema diffs configured")
                return@afterEvaluate
            }

            val gens: List<GenTasks> = diffs.mapNotNull { configureSchemaGenerationTasks(project, it) }

            val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
            val testJavaSS = javaExt.sourceSets.getByName("test")

            // If Kotlin plugin is applied, we’ll add the GRT sources to the Kotlin 'test' source set
            var addToKotlinTest: ((Any) -> Unit)? = null
            project.plugins.withId("org.jetbrains.kotlin.jvm") {
                val kext = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java)
                val testK = kext.sourceSets.getByName("test")
                addToKotlinTest = { provider -> testK.kotlin.srcDir(provider) }
            }

            gens.forEach { g ->
                // 1) SCHEMA task (bytecode): add its classes dir to the *output* of the test source set
                //    This puts the produced .class files on both compile & runtime classpaths for 'test'.
                testJavaSS.output.dir(
                    mapOf("builtBy" to g.schema),
                    g.schema.flatMap { it.generatedSrcDir }
                )

                // Also ensure the usual aggregators depend on it (no circular edges here).
                project.tasks.named("testClasses").configure { dependsOn(g.schema) }
                project.plugins.withId("org.jetbrains.kotlin.jvm") {
                    project.tasks.named("compileTestKotlin").configure { dependsOn(g.schema) }
                }

                // 2) GRT task (Kotlin sources): add as sources to test (both Kotlin + Java for IDEs)
                addToKotlinTest?.invoke(g.grt.flatMap { it.generatedSrcDir })
                testJavaSS.java.srcDir(g.grt.flatMap { it.generatedSrcDir })
            }
        }
    }

    private data class GenTasks(
        val schema: TaskProvider<ViaductClassDiffSchemaTask>,
        val grt: TaskProvider<ViaductClassDiffGRTKotlinTask>
    )

    private fun configureSchemaGenerationTasks(
        project: Project,
        schemaDiff: SchemaDiff
    ): GenTasks? {
        val schemaFiles = schemaDiff.resolveSchemaFiles()
        if (schemaFiles.isEmpty()) {
            project.logger.error("No valid schema files found for schema diff '${schemaDiff.name}'")
            return null
        }

        val schemaTask = configureSchemaGeneration(project, schemaDiff, schemaFiles)
        val grtTask = configureGRTGeneration(project, schemaDiff, schemaFiles)
        grtTask.configure { dependsOn(schemaTask) }

        return GenTasks(schema = schemaTask, grt = grtTask)
    }

    private fun configureSchemaGeneration(
        project: Project,
        schemaDiff: SchemaDiff,
        schemaFiles: List<File>
    ): TaskProvider<ViaductClassDiffSchemaTask> =
        project.tasks.register<ViaductClassDiffSchemaTask>(
            "generateSchemaDiff${schemaDiff.name.capitalize()}SchemaObjects"
        ) {
            group = PLUGIN_GROUP
            description = "Generates schema objects for schema diff '${schemaDiff.name}'"
            schemaName.set("default")
            packageName.set(schemaDiff.actualPackage.get())
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            workerNumber.set(0)
            workerCount.set(1)
            includeIneligibleForTesting.set(true)
            this.schemaFiles.from(schemaFiles)
            generatedSrcDir.set(project.layout.buildDirectory.dir(GENERATED_SOURCES_PATH))
            dependsOn("processResources")
            doFirst { generatedSrcDir.get().asFile.mkdirs() }
        }

    private fun configureGRTGeneration(
        project: Project,
        schemaDiff: SchemaDiff,
        schemaFiles: List<File>
    ): TaskProvider<ViaductClassDiffGRTKotlinTask> {
        val pkg = schemaDiff.expectedPackage.get()
        val pkgPath = pkg.replace(".", "/")

        return project.tasks.register<ViaductClassDiffGRTKotlinTask>(
            "generateSchemaDiff${schemaDiff.name.capitalize()}KotlinGrts"
        ) {
            group = PLUGIN_GROUP
            description = "Generates Kotlin GRTs for schema diff '${schemaDiff.name}'"
            this.schemaFiles.from(schemaFiles)
            packageName.set(pkg)
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            generatedSrcDir.set(project.layout.buildDirectory.dir("$GENERATED_SOURCES_PATH/$pkgPath"))
            dependsOn("processResources")
            doFirst { generatedSrcDir.get().asFile.mkdirs() }
        }
    }
}
