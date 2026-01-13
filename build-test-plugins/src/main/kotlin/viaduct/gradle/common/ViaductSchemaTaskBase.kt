package viaduct.gradle.common

import java.io.FileOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import viaduct.gradle.ViaductPluginCommon
import viaduct.graphql.schema.binary.writeBSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.tenant.codegen.cli.SchemaObjectsBytecode

/**
 * Base class for schema generation tasks.
 * Contains common functionality shared between viaduct-schema and viaduct-feature-app plugins.
 */
abstract class ViaductSchemaTaskBase : DefaultTask() {
    @get:Inject
    abstract val projectLayout: ProjectLayout

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
    abstract val schemaFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val generatedSrcDir: DirectoryProperty

    /**
     * Common schema generation logic that can be called by subclasses
     */
    protected fun executeSchemaGeneration() {
        val outputDir = generatedSrcDir.get().asFile

        // Write build flags to temporary file
        val flagFile = temporaryDir.resolve("viaduct_build_flags")
        flagFile.writeText(ViaductPluginCommon.buildFlagFileContent(buildFlags.get()))

        // Include the default schema along with the configured schema files
        val allSchemaFiles = DefaultSchemaUtil
            .getSchemaFilesIncludingDefault(schemaFiles, projectLayout, logger)
            .toList()
            .sortedBy { it.absolutePath }
        val schemaFilesArg = allSchemaFiles.joinToString(",") { it.absolutePath }
        val workerNumberArg = workerNumber.get().toString()
        val workerCountArg = workerCount.get().toString()
        val includeEligibleForTesting = includeIneligibleForTesting.get()

        // Generate binary schema file
        val binarySchemaFile = temporaryDir.resolve("schema.bgql")
        writeBSchema(
            GJSchema.fromFiles(allSchemaFiles),
            FileOutputStream(binarySchemaFile)
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

        try {
            SchemaObjectsBytecode.Main.main(
                finalArgs.toTypedArray()
            )
        } catch (e: Exception) {
            throw GradleException("SchemaObjectsBytecode execution failed: ${e.message}", e)
        }

        // Ensure the generated directory has content
        if (!outputDir.exists() || (outputDir.listFiles()?.isEmpty() != false)) {
            throw GradleException("Schema generation failed - no classes generated in ${outputDir.absolutePath}")
        }
    }
}
