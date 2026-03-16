package viaduct.gradle.common

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.workers.WorkerExecutor
import viaduct.gradle.shared.BuildFlags

/**
 * Base class for schema generation tasks.
 * Contains common functionality shared between viaduct-schema and viaduct-feature-app plugins.
 */
abstract class ViaductSchemaTaskBase : DefaultTask() {
    @get:Inject
    abstract val projectLayout: ProjectLayout

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val schemaName: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val buildFlags: MapProperty<String, String>

    @get:Input
    abstract val workerNumber: Property<Int>

    @get:Input
    abstract val workerCount: Property<Int>

    @get:Input
    abstract val includeIneligibleForTesting: Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val generatedSrcDir: DirectoryProperty

    /**
     * Common schema generation logic that can be called by subclasses
     */
    protected fun executeSchemaGeneration() {
        val outputDir = generatedSrcDir.get().asFile

        // Write build flags to temporary file
        val flagFile = temporaryDir.resolve("viaduct_build_flags")
        flagFile.writeText(BuildFlags.toFileContent(buildFlags.get()))

        // Include the default schema along with the configured schema files
        val allSchemaFiles = DefaultSchemaUtil
            .getSchemaFilesIncludingDefault(schemaFiles, projectLayout, logger)
            .toList()
            .sortedBy { it.absolutePath }
        val schemaFilesArg = allSchemaFiles.joinToString(",") { it.absolutePath }
        val workerNumberArg = workerNumber.get().toString()
        val workerCountArg = workerCount.get().toString()
        val includeEligibleForTesting = includeIneligibleForTesting.get()

        // Generate binary schema file via isolated classloader
        val binarySchemaFile = temporaryDir.resolve("schema.bgql")
        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.BINARY_SCHEMA_GENERATOR,
            listOf(
                "--schema_files",
                allSchemaFiles.joinToString(",") { it.absolutePath },
                "--output_file",
                binarySchemaFile.absolutePath
            )
        )

        // Clean and prepare directories
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        val baseArgs = mutableListOf(
            "--generated_directory",
            outputDir.absolutePath,
            "--schema_files",
            schemaFilesArg,
            "--binary_schema_file",
            binarySchemaFile.absolutePath,
            "--flag_file",
            flagFile.absolutePath,
            "--bytecode_worker_number",
            workerNumberArg,
            "--bytecode_worker_count",
            workerCountArg,
            "--pkg_for_generated_classes",
            packageName.get()
        )

        val finalArgs = if (includeEligibleForTesting) {
            baseArgs + "--include_ineligible_for_testing_only"
        } else {
            baseArgs
        }

        // Run schema codegen via isolated classloader
        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.SCHEMA_OBJECTS_BYTECODE,
            finalArgs
        )

        // Ensure the generated directory has content
        if (!outputDir.exists() || (outputDir.listFiles()?.isEmpty() != false)) {
            throw GradleException("Schema generation failed - no classes generated in ${outputDir.absolutePath}")
        }
    }
}
