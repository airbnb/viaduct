package viaduct.gradle.classdiff

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
import org.gradle.api.tasks.TaskAction
import viaduct.gradle.ViaductPluginCommon
import viaduct.gradle.common.DefaultSchemaUtil
import viaduct.graphql.schema.binary.writeBSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.tenant.codegen.cli.KotlinGRTsGenerator

/**
 * Task to generate Kotlin GRT (GraphQL Runtime Types) for ClassDiff tests.
 *
 * This task generates Kotlin GRT classes from GraphQL schemas for ClassDiff testing.
 * It takes schema files referenced in ClassDiff test classes and generates the
 * corresponding Kotlin runtime types that can be used in test scenarios.
 *
 * The generated classes are placed in a package structure that matches the
 * ClassDiff test naming convention, allowing for isolated testing of different
 * GraphQL schemas without conflicts.
 */
abstract class ViaductClassDiffGRTKotlinTask : DefaultTask() {
    @get:Inject
    abstract val projectLayout: ProjectLayout

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val buildFlags: MapProperty<String, String>

    @get:InputFiles
    abstract val schemaFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val generatedSrcDir: DirectoryProperty

    /**
     * Executes the Kotlin GRT generation process.
     *
     * This method:
     * 1. Prepares the output directory
     * 2. Writes build flags to a temporary file
     * 3. Generates binary schema file
     * 4. Collects schema files and classpath
     * 5. Executes the KotlinGRTsGenerator
     * 6. Validates the generation was successful
     */
    @TaskAction
    protected fun executeGRTGeneration() {
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

        // Generate binary schema file
        val binarySchemaFile = temporaryDir.resolve("schema.bgql")
        writeBSchema(
            GJSchema.fromFiles(allSchemaFiles),
            FileOutputStream(binarySchemaFile)
        )

        // Clean and prepare directories
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        val generationArgs = listOf(
            "--generated_directory",
            outputDir.absolutePath,
            "--schema_files",
            schemaFilesArg,
            "--binary_schema_file",
            binarySchemaFile.absolutePath,
            "--flag_file",
            flagFile.absolutePath,
            "--pkg_for_generated_classes",
            packageName.get()
        )

        try {
            KotlinGRTsGenerator.Main.main(generationArgs.toTypedArray())
        } catch (e: Exception) {
            throw GradleException("Kotlin GRT generation failed: ${e.message}", e)
        }

        // Validate generation was successful
        if (!outputDir.exists() || (outputDir.listFiles()?.isEmpty() != false)) {
            throw GradleException("Kotlin GRT generation failed - no classes generated in ${outputDir.absolutePath}")
        }

        logger.info("Successfully generated Kotlin GRTs in package '${packageName.get()}' at ${outputDir.absolutePath}")
    }
}
