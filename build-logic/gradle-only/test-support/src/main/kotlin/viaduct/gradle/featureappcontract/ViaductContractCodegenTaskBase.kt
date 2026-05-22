package viaduct.gradle.featureappcontract

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.WorkerExecutor

/**
 * Abstract base for contract codegen tasks.
 *
 * Iterates over `schema.graphql` files extracted by the publisher, generating
 * GRTs and resolver base classes for each contract. Uses `@Incremental` so only
 * changed schemas trigger codegen.
 *
 * Each contract generates into its own isolated per-contract directory (because
 * codegen CLIs clean their output directories and some write flat). Results are
 * then merged into shared output roots for compilation.
 */
@CacheableTask
abstract class ViaductContractCodegenTaskBase : DefaultTask() {
    @get:Incremental
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractSchemaDir: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val defaultSchemaFile: RegularFileProperty

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    /** Merged output root for GRTs (bytecode for Kotlin, source for Java). */
    @get:OutputDirectory
    abstract val grtOutputDir: DirectoryProperty

    /** Merged output root for generated resolver base sources. */
    @get:OutputDirectory
    abstract val tenantOutputDir: DirectoryProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun generate(inputChanges: InputChanges) {
        val schemasDir = contractSchemaDir.singleFile
        val perContractRoot = grtOutputDir.get().asFile.parentFile.resolve("per-contract")

        if (!inputChanges.isIncremental) {
            logger.info("Non-incremental build — regenerating all contracts")
            cleanAllOutputs(perContractRoot)
            for (schemaFile in walkSchemaFiles(schemasDir)) {
                generateAndMerge(schemasDir, schemaFile, perContractRoot)
            }
            return
        }

        for (change in inputChanges.getFileChanges(contractSchemaDir)) {
            if (change.file.name != "schema.graphql") continue
            val pkgPath = change.file.parentFile.relativeTo(schemasDir).path
            val pkg = pkgPath.replace(File.separatorChar, '.')

            when (change.changeType) {
                ChangeType.REMOVED -> {
                    logger.info("Contract removed: {}", pkg)
                    File(perContractRoot, pkg).deleteRecursively()
                    removeFromMergedOutputs(pkgPath)
                }
                ChangeType.ADDED, ChangeType.MODIFIED -> {
                    logger.info("Contract {}: {}", change.changeType.name.lowercase(), pkg)
                    generateAndMerge(schemasDir, change.file, perContractRoot)
                }
            }
        }
    }

    /**
     * Subclass hook: run language-specific codegen for a single contract schema.
     *
     * @param schemaFile  the extracted `schema.graphql` file
     * @param pkg         the contract's fully-qualified package name
     * @param grtDir      isolated per-contract GRT output directory (clean before use)
     * @param tenantDir   isolated per-contract tenant output directory (clean before use)
     */
    protected abstract fun generateForSchema(
        schemaFile: File,
        pkg: String,
        grtDir: File,
        tenantDir: File
    )

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun generateAndMerge(
        schemasDir: File,
        schemaFile: File,
        perContractRoot: File
    ) {
        val pkgPath = schemaFile.parentFile.relativeTo(schemasDir).path
        val pkg = pkgPath.replace(File.separatorChar, '.')

        // Generate into isolated per-contract dirs
        val contractDir = File(perContractRoot, pkg)
        val grtDir = File(contractDir, "grts")
        val tenantDir = File(contractDir, "tenant")
        grtDir.deleteRecursively()
        grtDir.mkdirs()
        tenantDir.deleteRecursively()
        tenantDir.mkdirs()

        generateForSchema(schemaFile, pkg, grtDir, tenantDir)

        // Merge into output roots
        mergeDir(grtDir, grtOutputDir.get().asFile, pkgPath)
        mergeTenantDir(tenantDir, tenantOutputDir.get().asFile, pkg, pkgPath)
    }

    private fun cleanAllOutputs(perContractRoot: File) {
        perContractRoot.deleteRecursively()
        grtOutputDir.get().asFile.deleteRecursively()
        tenantOutputDir.get().asFile.deleteRecursively()
        grtOutputDir.get().asFile.mkdirs()
        tenantOutputDir.get().asFile.mkdirs()
    }

    private fun removeFromMergedOutputs(pkgPath: String) {
        val pkg = pkgPath.replace(File.separatorChar, '.')
        File(grtOutputDir.get().asFile, pkgPath).deleteRecursively()
        // Tenant removal uses pkg (dots) for flat generators and pkgPath for tree generators
        File(tenantOutputDir.get().asFile, pkg).deleteRecursively()
        File(tenantOutputDir.get().asFile, pkgPath).deleteRecursively()
    }

    /**
     * Merges tenant source output from an isolated per-contract dir into the merged root.
     *
     * Default implementation preserves the directory structure (works for generators
     * that create package subdirectories, like the Java GRT generator's resolver mode).
     *
     * Subclasses override this for generators that write flat (like ViaductGenerator
     * for Kotlin), which need files placed in a per-package subdirectory to prevent
     * collisions (multiple contracts produce `QueryResolvers.kt`).
     */
    protected open fun mergeTenantDir(
        tenantDir: File,
        mergedTenantRoot: File,
        pkg: String,
        pkgPath: String
    ) {
        // Default: preserve directory structure (Java resolver generator creates subdirs)
        mergeDir(tenantDir, mergedTenantRoot, pkgPath)
    }

    /**
     * Merges output from an isolated per-contract dir into a merged output root,
     * preserving the directory structure.
     *
     * Clears the corresponding [pkgPath] subdirectory in the merged root before
     * copying, so stale files from previous runs are removed.
     */
    private fun mergeDir(
        sourceDir: File,
        mergedRoot: File,
        pkgPath: String
    ) {
        // The codegen CLIs create package subdirs inside sourceDir, so files live at
        // sourceDir/<pkgPath>/Foo.class. The delete and copy zones are consistent because
        // relPath always starts with pkgPath.
        //
        // deleteRecursively() removes this contract's full output subtree (including
        // nested subdirs like resolverbases/) without touching sibling contracts. This is
        // safe because no contract's pkgPath may be a prefix of another contract's pkgPath
        // — i.e., contracts must not be nested inside one another's package.
        File(mergedRoot, pkgPath).deleteRecursively()
        if (sourceDir.exists() && sourceDir.listFiles()?.isNotEmpty() == true) {
            sourceDir.walkTopDown().filter { it.isFile }.forEach { src ->
                val relPath = src.relativeTo(sourceDir).path
                val dest = File(mergedRoot, relPath)
                dest.parentFile.mkdirs()
                src.copyTo(dest, overwrite = true)
            }
        }
    }

    private fun walkSchemaFiles(schemasDir: File): List<File> {
        if (!schemasDir.exists()) return emptyList()
        return schemasDir.walkTopDown()
            .filter { it.isFile && it.name == "schema.graphql" }
            .toList()
    }
}
