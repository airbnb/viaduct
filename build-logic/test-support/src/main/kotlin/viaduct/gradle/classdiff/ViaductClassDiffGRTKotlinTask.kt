package viaduct.gradle.classdiff

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
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import viaduct.gradle.common.CodegenWorkAction
import viaduct.gradle.common.DefaultSchemaUtil
import viaduct.gradle.common.runCodegen
import viaduct.gradle.shared.BuildFlags

/**
 * Task to generate Kotlin GRT (GraphQL Runtime Types) for ClassDiff tests.
 */
abstract class ViaductClassDiffGRTKotlinTask : DefaultTask() {
    @get:Inject
    abstract val projectLayout: ProjectLayout

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val buildFlags: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val generatedSrcDir: DirectoryProperty

    @TaskAction
    protected fun executeGRTGeneration() {
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

        // Run GRT codegen via isolated classloader
        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.KOTLIN_GRTS_GENERATOR,
            generationArgs
        )

        // Validate generation was successful
        if (!outputDir.exists() || (outputDir.listFiles()?.isEmpty() != false)) {
            throw GradleException("Kotlin GRT generation failed - no classes generated in ${outputDir.absolutePath}")
        }

        logger.info("Successfully generated Kotlin GRTs in package '${packageName.get()}' at ${outputDir.absolutePath}")
    }
}
