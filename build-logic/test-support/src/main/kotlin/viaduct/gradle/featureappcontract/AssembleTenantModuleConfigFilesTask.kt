package viaduct.gradle.featureappcontract

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import viaduct.gradle.common.CodegenWorkAction
import viaduct.gradle.common.runCodegen

/**
 * Assembles tenant module config files by invoking the aggregation CLI once per
 * contract. Each contract is identified by a `schema.graphql` file in the
 * [contractSchemaDir]; its package is derived from the directory path.
 *
 * For each contract, the CLI receives:
 * - The descriptor directory scoped to that contract's package path within [descriptorDir]
 * - The contract's `schema.graphql` file
 * - The tenant package name (derived from the schema file's directory path)
 *
 * Output: one `META-INF/viaduct/modules/<tenantpkg>.json` per contract in [outputDir].
 */
@CacheableTask
abstract class AssembleTenantModuleConfigFilesTask : DefaultTask() {
    /** KSP descriptor root: `viaduct-registry/` within KSP's resource output. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val descriptorDir: DirectoryProperty

    /** Directory containing extracted contract schemas (one `schema.graphql` per package path). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractSchemaDir: ConfigurableFileCollection

    /** Codegen tool classpath (carries the aggregation CLI). */
    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    /** Output directory for generated config files. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun assemble() {
        val schemasDir = contractSchemaDir.singleFile
        val descriptorRoot = descriptorDir.get().asFile

        val schemaFiles = schemasDir.walkTopDown()
            .filter { it.isFile && it.name == "schema.graphql" }
            .toList()

        for (schemaFile in schemaFiles) {
            val pkgPath = schemaFile.parentFile.relativeTo(schemasDir).path
            val pkg = pkgPath.replace(File.separatorChar, '.')

            // The descriptor directory for this contract's package
            val contractDescriptorDir = File(descriptorRoot, pkgPath)

            if (!contractDescriptorDir.exists()) {
                logger.warn("No descriptors found for contract package {} — skipping", pkg)
                continue
            }

            workerExecutor.runCodegen(
                codegenClasspath,
                CodegenWorkAction.MainClasses.ASSEMBLE_TENANT_MODULE_CONFIG_FILE,
                listOf(
                    "--descriptor-dir",
                    contractDescriptorDir.absolutePath,
                    "--schema-file",
                    schemaFile.absolutePath,
                    "--tenant-package",
                    pkg,
                    "--output-dir",
                    outputDir.get().asFile.absolutePath,
                ),
            )
        }
    }
}
