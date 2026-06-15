package viaduct.gradle.featureappcontract

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileType
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.FileChange
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.WorkerExecutor
import viaduct.gradle.common.CodegenWorkAction
import viaduct.gradle.common.runCodegen

/**
 * Assembles tenant module config files by invoking the aggregation CLI once per
 * contract. Each contract is identified by a `schema.graphql` file in the
 * [contractSchemaDir]; its package is derived from the directory path.
 *
 * Supports incremental builds: when only some descriptor or schema files change,
 * only the affected contracts are re-assembled. Package identity is determined
 * from file paths within [descriptorDir] and [contractSchemaDir].
 *
 * For each contract, the CLI receives:
 * - The descriptor directory scoped to that contract's package path within [descriptorDir]
 *   (rooted at `viaduct-registry/` in the KSP resource output)
 * - The contract's `schema.graphql` file
 * - The tenant package name (derived from the schema file's directory path)
 *
 * Output: one `META-INF/viaduct/modules/<tenantpkg>.json` per contract in [outputDir].
 */
@CacheableTask
abstract class AssembleTenantModuleConfigFilesTask : DefaultTask(), IncrementalActions {
    /**
     * KSP descriptor root: `viaduct-registry/` within KSP's resource output.
     *
     * Annotated with `@InputFiles` (not `@InputDirectory`) because the directory may not
     * exist if no `@Resolver` classes are present. `@Optional` prevents Gradle from failing
     * when absent. `@Incremental` enables per-file change tracking so only affected
     * contracts are re-assembled.
     */
    @get:Incremental
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val descriptorDir: DirectoryProperty

    /**
     * Directory containing extracted contract schemas (one `schema.graphql` per package path).
     *
     * `@Incremental` so that a schema change only re-assembles the affected contract
     * rather than forcing a full rebuild. `@Optional` because the directory may not
     * exist if no contracts are configured.
     */
    @get:Incremental
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractSchemaDir: DirectoryProperty

    /** Codegen tool classpath (carries the aggregation CLI). */
    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    /**
     * FQN of the [viaduct.engine.api.spi.ExecutorFactory] implementation to record in each
     * generated registry file. Defaults to the Kotlin tenant-API factory ([MODERN_KOTLIN_EXECUTOR_FACTORY])
     * when unset; the Java contract wiring overrides it with [JAVA_EXECUTOR_FACTORY].
     */
    @get:Input
    @get:Optional
    abstract val executorFactory: Property<String>

    /** Output directory for generated config files. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun assemble(inputChanges: InputChanges) {
        if (!contractSchemaDir.isPresent || !descriptorDir.isPresent) {
            logger.info("No contract schemas or KSP descriptors configured — nothing to assemble")
            return
        }
        val schemasDir = contractSchemaDir.get().asFile
        val descriptorRoot = descriptorDir.get().asFile

        if (!inputChanges.isIncremental) {
            // Full rebuild: invoke CLI once per contract that has descriptors.
            // No manual output cleanup — Gradle handles it for @OutputDirectory.
            val schemaFiles = schemasDir.walkTopDown()
                .filter { it.isFile && it.name == "schema.graphql" }
                .toList()

            for (schemaFile in schemaFiles) {
                val pkgPath = schemaFile.parentFile.relativeTo(schemasDir).path
                if (goneOrEmpty(File(descriptorRoot, pkgPath))) continue
                assembleForSchema(pkgPath, descriptorRoot, schemaFile)
            }
            return
        }

        processChanges(
            descriptorRoot,
            schemasDir,
            inputChanges.getFileChanges(descriptorDir),
            inputChanges.getFileChanges(contractSchemaDir),
        )
    }

    // ── IncrementalActions implementation ────────────────────────────────────

    override fun assembleForSchema(
        pkgPath: String,
        descriptorRoot: File,
        schemaFile: File
    ) {
        val pkg = pkgPath.replace(File.separatorChar, '.')
        val contractDescriptorDir = File(descriptorRoot, pkgPath)

        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.ASSEMBLE_TENANT_MODULE_CONFIG_FILE,
            listOf(
                "--descriptor-dir",
                contractDescriptorDir.absolutePath,
                "--tenant-package",
                pkg,
                "--executor-factory",
                executorFactory.getOrElse(MODERN_KOTLIN_EXECUTOR_FACTORY),
                "--output-dir",
                outputDir.get().asFile.absolutePath,
            ),
        )
    }

    override fun deleteConfig(pkg: String) {
        val configFile = outputDir.get().asFile
            .resolve("META-INF/viaduct/modules/$pkg.json")
        configFile.delete()
        logger.info("Removed config for package {}", pkg)
    }

    override fun goneOrEmpty(dir: File): Boolean = !dir.exists() || dir.listFiles()?.isEmpty() != false

    override fun gone(file: File): Boolean = !file.exists()

    companion object {
        /**
         * Incremental logic extracted as a static extension function for unit testability.
         * Given file changes from both the descriptor and schema inputs, determines which
         * packages are affected and invokes the appropriate action (re-assemble or delete).
         */
        internal fun IncrementalActions.processChanges(
            descriptorRoot: File,
            schemasDir: File,
            descriptorChanges: Iterable<FileChange>,
            schemaChanges: Iterable<FileChange>,
        ) {
            val affectedPackages = mutableSetOf<String>()

            for (change in descriptorChanges) {
                require(change.fileType == FileType.FILE) {
                    "Unexpected directory in descriptor input: ${change.file.relativeTo(descriptorRoot).path}"
                }
                val relPath = change.file.relativeTo(descriptorRoot).path
                require(change.file.extension == "json") {
                    "Unexpected non-JSON file in descriptor directory: $relPath"
                }
                val pkgPath = requireNotNull(File(relPath).parent) {
                    "Descriptor file $relPath has no package path — " +
                        "expected viaduct-registry/<package-path>/<File>.json"
                }
                affectedPackages.add(pkgPath)
            }

            for (change in schemaChanges) {
                require(change.fileType == FileType.FILE) {
                    "Unexpected directory in contract schema input: " +
                        change.file.relativeTo(schemasDir).path
                }
                require(change.file.name == "schema.graphql") {
                    "Unexpected file in contract schema directory: " +
                        "${change.file.relativeTo(schemasDir).path} " +
                        "(expected only schema.graphql files)"
                }
                val pkgPath = change.file.parentFile.relativeTo(schemasDir).path
                affectedPackages.add(pkgPath)
            }

            for (pkgPath in affectedPackages) {
                val pkg = pkgPath.replace(File.separatorChar, '.')
                val schemaFile = File(schemasDir, "$pkgPath/schema.graphql")
                val contractDescriptorDir = File(descriptorRoot, pkgPath)

                if (goneOrEmpty(contractDescriptorDir) || gone(schemaFile)) {
                    deleteConfig(pkg)
                    continue
                }

                assembleForSchema(pkgPath, descriptorRoot, schemaFile)
            }
        }
    }
}

internal const val MODERN_KOTLIN_EXECUTOR_FACTORY = "viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory"

internal const val JAVA_EXECUTOR_FACTORY = "viaduct.java.runtime.bootstrap.ViaductJavaExecutorFactory"

/**
 * Actions that the incremental logic can perform. The task implements this
 * directly; tests provide a recording fake to verify which actions were taken.
 */
internal interface IncrementalActions {
    fun assembleForSchema(
        pkgPath: String,
        descriptorRoot: File,
        schemaFile: File
    )

    fun deleteConfig(pkg: String)

    fun goneOrEmpty(dir: File): Boolean

    fun gone(file: File): Boolean
}
