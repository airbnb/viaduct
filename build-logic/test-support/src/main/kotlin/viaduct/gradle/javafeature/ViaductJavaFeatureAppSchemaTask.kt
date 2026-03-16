package viaduct.gradle.javafeature

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
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

/**
 * Task to generate Java GRT schema objects for viaduct Java feature app tests.
 * Uses process isolation to invoke [viaduct.x.javaapi.codegen.cli.JavaGRTsGenerator]
 * from the codegen classpath, generating Java GRT types from GraphQL schema files.
 *
 * Mirrors [viaduct.gradle.feature.ViaductFeatureAppSchemaTask] but uses
 * Java codegen instead of Kotlin bytecode codegen.
 */
abstract class ViaductJavaFeatureAppSchemaTask : DefaultTask() {
    @get:Inject
    abstract val projectLayout: ProjectLayout

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:Input
    abstract val packageName: Property<String>

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val generatedSrcDir: DirectoryProperty

    @TaskAction
    fun generateFeatureAppSchema() {
        val allSchemaFiles = DefaultSchemaUtil.getSchemaFilesIncludingDefault(
            schemaFiles,
            projectLayout,
            logger
        ).toList().sortedBy { it.absolutePath }

        val outputDir = generatedSrcDir.get().asFile
        val resolverTempDir = temporaryDir.resolve("resolvers")

        // Clean and prepare output directory
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()
        resolverTempDir.mkdirs()

        // Generate GRTs via process-isolated worker using the Java codegen CLI.
        // The CLI also generates resolvers; we discard them here (tenant task handles that).
        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.JAVA_GRTS_GENERATOR,
            listOf(
                "--schema_files",
                allSchemaFiles.joinToString(",") { it.absolutePath },
                "--grt_output_dir",
                outputDir.absolutePath,
                "--grt_package",
                packageName.get(),
                "--resolver_generated_dir",
                resolverTempDir.absolutePath,
                "--tenant_package",
                packageName.get(),
                "--include_root_types"
            )
        )

        // Ensure the generated directory has content
        if (!outputDir.exists() || (outputDir.listFiles()?.isEmpty() != false)) {
            throw GradleException("Schema generation failed - no classes generated in ${outputDir.absolutePath}")
        }
    }
}
