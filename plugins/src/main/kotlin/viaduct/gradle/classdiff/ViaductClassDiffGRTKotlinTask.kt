package viaduct.gradle.classdiff

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

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
    abstract val execOperations: ExecOperations

    @get:Input
    abstract val javaExecutable: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:InputFiles
    abstract val schemaFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val codegenClassPath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val generatedSrcDir: DirectoryProperty

    /**
     * Executes the Kotlin GRT generation process.
     *
     * This method:
     * 1. Prepares the output directory
     * 2. Collects schema files and classpath
     * 3. Executes the KotlinGRTsGenerator
     * 4. Validates the generation was successful
     */
    @TaskAction
    protected fun executeGRTGeneration() {
        val outputDir = generatedSrcDir.get().asFile
        val schemaFilesArg = schemaFiles.files.joinToString(",") { it.absolutePath }

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
            "--pkg_for_generated_classes",
            packageName.get()
        )

        val result = execOperations.exec {
            executable = javaExecutable.get()
            args = listOf(
                "-cp",
                codegenClassPath.asPath,
                "viaduct.tenant.codegen.cli.KotlinGRTsGenerator\$Main"
            ) + generationArgs
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw GradleException("Kotlin GRT generation failed with exit code ${result.exitValue}")
        }

        // Validate generation was successful
        if (!outputDir.exists() || (outputDir.listFiles()?.isEmpty() != false)) {
            throw GradleException("Kotlin GRT generation failed - no classes generated in ${outputDir.absolutePath}")
        }

        logger.info("Successfully generated Kotlin GRTs in package '${packageName.get()}' at ${outputDir.absolutePath}")
    }
}
