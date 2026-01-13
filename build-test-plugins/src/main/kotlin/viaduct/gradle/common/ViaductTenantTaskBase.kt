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
import viaduct.tenant.codegen.cli.ViaductGenerator

/**
 * Base class for tenant generation tasks.
 * Contains common functionality shared between viaduct-schema and viaduct-feature-app plugins.
 */
abstract class ViaductTenantTaskBase : DefaultTask() {
    @get:Input
    abstract val featureAppTest: Boolean

    @get:Input
    abstract val tenantName: Property<String>

    @get:Input
    abstract val buildFlags: MapProperty<String, String>

    @get:Input
    abstract val packageNamePrefix: Property<String>

    @get:Input
    abstract val tenantFromSourceNameRegex: Property<String>

    @get:InputFiles
    abstract val schemaFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val modernModuleSrcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resolverSrcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val metaInfSrcDir: DirectoryProperty

    @get:Inject
    abstract val projectLayout: ProjectLayout

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
        flagFile.writeText(ViaductPluginCommon.buildFlagFileContent(buildFlags.get()))

        // Include the default schema along with the configured schema files
        val allSchemaFiles = DefaultSchemaUtil
            .getSchemaFilesIncludingDefault(schemaFiles, projectLayout, logger)
            .toList()
            .sortedBy { it.absolutePath }

        // Generate binary schema file
        val binarySchemaFile = temporaryDir.resolve("schema.bgql")
        writeBSchema(
            GJSchema.fromFiles(allSchemaFiles),
            FileOutputStream(binarySchemaFile)
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

        val finalArgs = if (featureAppTest) {
            baseArgs + "--isFeatureAppTest"
        } else {
            baseArgs
        }

        try {
            ViaductGenerator.Main.main(finalArgs.toTypedArray())
        } catch (e: Exception) {
            throw GradleException("ViaductGenerator execution failed: ${e.message}", e)
        }
    }
}
