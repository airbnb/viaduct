package viaduct.gradle.javafeature

import javax.inject.Inject
import org.gradle.api.DefaultTask
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
 * Task to generate Java resolver base classes and argument types for viaduct
 * Java feature app tests.
 * Uses process isolation to invoke [viaduct.x.javaapi.codegen.cli.JavaGRTsGenerator]
 * from the codegen classpath, generating resolver base classes from GraphQL schema files.
 *
 * Mirrors [viaduct.gradle.feature.ViaductFeatureAppTenantTask] but uses
 * Java codegen instead of Kotlin bytecode codegen.
 */
abstract class ViaductJavaFeatureAppTenantTask : DefaultTask() {
    @get:Inject
    abstract val projectLayout: ProjectLayout

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:Input
    abstract val grtPackage: Property<String>

    @get:Input
    abstract val tenantPackage: Property<String>

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val resolverSrcDir: DirectoryProperty

    @TaskAction
    fun generateFeatureAppTenant() {
        val allSchemaFiles = DefaultSchemaUtil.getSchemaFilesIncludingDefault(
            schemaFiles,
            projectLayout,
            logger
        ).toList().sortedBy { it.absolutePath }

        val resolverDir = resolverSrcDir.get().asFile
        val grtTempDir = temporaryDir.resolve("grts")

        // Clean and prepare output directory
        resolverDir.deleteRecursively()
        resolverDir.mkdirs()
        grtTempDir.mkdirs()

        // Generate resolvers via process-isolated worker using the Java codegen CLI.
        // The CLI also generates GRTs; we discard them here (schema task handles that).
        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.JAVA_GRTS_GENERATOR,
            listOf(
                "--schema_files",
                allSchemaFiles.joinToString(",") { it.absolutePath },
                "--grt_output_dir",
                grtTempDir.absolutePath,
                "--grt_package",
                grtPackage.get(),
                "--resolver_generated_dir",
                resolverDir.absolutePath,
                "--tenant_package",
                tenantPackage.get()
            )
        )

        logger.info("Java feature app tenant codegen complete")
    }
}
