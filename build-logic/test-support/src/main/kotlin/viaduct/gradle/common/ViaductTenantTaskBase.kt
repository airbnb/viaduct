package viaduct.gradle.common

import javax.inject.Inject
import org.gradle.api.DefaultTask
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
 * Base class for tenant generation tasks.
 * Contains common functionality shared between viaduct-schema and viaduct-feature-app plugins.
 */
abstract class ViaductTenantTaskBase : DefaultTask() {
    @get:Input
    abstract val featureAppTest: Property<Boolean>

    @get:Input
    abstract val tenantName: Property<String>

    @get:Input
    abstract val buildFlags: MapProperty<String, String>

    @get:Input
    abstract val packageNamePrefix: Property<String>

    @get:Input
    abstract val tenantFromSourceNameRegex: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val modernModuleSrcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resolverSrcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val metaInfSrcDir: DirectoryProperty

    @get:Inject
    abstract val projectLayout: ProjectLayout

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    /**
     * Common tenant generation logic that can be called by subclasses
     */
    protected fun executeTenantGeneration() {
        // Get temporary generation directories
        val modernModuleSrcDirFile = modernModuleSrcDir.get().asFile
        val resolverSrcDirFile = resolverSrcDir.get().asFile
        val metaInfSrcDirFile = metaInfSrcDir.get().asFile

        // Ensure directories exist
        modernModuleSrcDirFile.mkdirs()
        resolverSrcDirFile.mkdirs()
        metaInfSrcDirFile.mkdirs()

        // Skip if no schema files
        if (schemaFiles.isEmpty) {
            return
        }

        // Write build flags to temporary file
        val flagFile = temporaryDir.resolve("viaduct_build_flags")
        flagFile.writeText(BuildFlags.toFileContent(buildFlags.get()))

        // Include the default schema along with the configured schema files
        val allSchemaFiles = DefaultSchemaUtil
            .getSchemaFilesIncludingDefault(schemaFiles, projectLayout, logger)
            .toList()
            .sortedBy { it.absolutePath }

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

        // Build arguments for code generation
        val baseArgs = mutableListOf(
            "--tenant_pkg",
            tenantName.get(),
            "--schema_files",
            allSchemaFiles.joinToString(",") { it.absolutePath },
            "--binary_schema_file",
            binarySchemaFile.absolutePath,
            "--flag_file",
            flagFile.absolutePath,
            "--modern_module_generated_directory",
            modernModuleSrcDirFile.absolutePath,
            "--resolver_generated_directory",
            resolverSrcDirFile.absolutePath,
            "--metainf_generated_directory",
            metaInfSrcDirFile.absolutePath,
            "--tenant_package_prefix",
            packageNamePrefix.get(),
            "--tenant_from_source_name_regex",
            tenantFromSourceNameRegex.get()
        )

        val finalArgs = if (featureAppTest.get()) {
            baseArgs + "--isFeatureAppTest"
        } else {
            baseArgs
        }

        // Run tenant codegen via isolated classloader
        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.VIADUCT_GENERATOR,
            finalArgs
        )
    }
}
