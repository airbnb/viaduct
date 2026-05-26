package viaduct.gradle.featureappcontract

import java.io.File
import org.gradle.api.tasks.CacheableTask
import viaduct.gradle.common.CodegenWorkAction
import viaduct.gradle.common.runCodegen

/**
 * Kotlin contract codegen task.
 *
 * For each contract schema, generates:
 * 1. Bytecode GRTs via `SchemaObjectsBytecode` — `.class` files with package subdirs
 * 2. Resolver base sources via `ViaductGenerator` — `.kt` files written flat
 *
 * Both generate into isolated per-contract directories (because `SchemaObjectsBytecode`
 * cleans its output dir and `ViaductGenerator` writes flat), then results are merged
 * by the base class. The flat Kotlin resolver files are placed in per-package
 * subdirectories to prevent name collisions (multiple contracts produce
 * `QueryResolvers.kt`).
 */
@CacheableTask
abstract class KotlinContractCodegenTask : ContractCodegenTaskBase() {
    /**
     * ViaductGenerator writes flat — all files land directly in `tenantDir`.
     * Override the merge to place each contract's files in a per-package subdirectory
     * (using dots as the dir name, since Kotlin doesn't require dir-package matching).
     */
    override fun mergeTenantDir(
        tenantDir: File,
        mergedTenantRoot: File,
        pkg: String,
        pkgPath: String
    ) {
        val targetDir = File(mergedTenantRoot, pkg)
        targetDir.deleteRecursively()
        if (tenantDir.exists()) {
            val files = tenantDir.walkTopDown().filter { it.isFile }.toList()
            if (files.isNotEmpty()) {
                targetDir.mkdirs()
                files.forEach { src ->
                    val relPath = src.relativeTo(tenantDir).path
                    val dest = File(targetDir, relPath)
                    dest.parentFile.mkdirs()
                    src.copyTo(dest, overwrite = true)
                }
            }
        }
    }

    override fun generateForSchema(
        schemaFile: File,
        pkg: String,
        grtDir: File,
        tenantDir: File
    ) {
        val allSchemaFiles = listOf(schemaFile, defaultSchemaFile.get().asFile)
        val schemaFilesArg = allSchemaFiles.joinToString(",") { it.absolutePath }

        // Step 1: Generate bytecode GRTs via SchemaObjectsBytecode
        // No --flag_file or --binary_schema_file: the CLI reads .graphqls directly.
        // This CLI cleans --generated_directory, hence the isolated grtDir.
        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.SCHEMA_OBJECTS_BYTECODE,
            listOf(
                "--generated_directory",
                grtDir.absolutePath,
                "--schema_files",
                schemaFilesArg,
                "--bytecode_worker_number",
                "0",
                "--bytecode_worker_count",
                "1",
                "--pkg_for_generated_classes",
                pkg,
                "--include_ineligible_for_testing_only"
            )
        )

        // Step 2: Generate resolver base sources via ViaductGenerator
        // No --flag_file or --binary_schema_file: the CLI reads .graphqls directly.
        // This CLI writes flat (no package subdirs). Each contract has its own
        // isolated tenantDir, and mergeTenantDir places files in a per-package subdir.
        val tenantName = pkg.substringAfterLast('.')
        val tenantPackagePrefix = pkg.substringBeforeLast('.')

        // Discard dirs for modern module and META-INF (not needed for contract tests)
        val discardModernModule = temporaryDir.resolve("discard-modernmodule")
        discardModernModule.deleteRecursively()
        discardModernModule.mkdirs()
        val discardMetaInf = temporaryDir.resolve("discard-metainf")
        discardMetaInf.deleteRecursively()
        discardMetaInf.mkdirs()

        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.VIADUCT_GENERATOR,
            listOf(
                "--tenant_pkg",
                tenantName,
                "--schema_files",
                schemaFilesArg,
                "--modern_module_generated_directory",
                discardModernModule.absolutePath,
                "--resolver_generated_directory",
                tenantDir.absolutePath,
                "--metainf_generated_directory",
                discardMetaInf.absolutePath,
                "--tenant_package_prefix",
                tenantPackagePrefix,
                "--tenant_from_source_name_regex",
                "(.*)",
                "--isFeatureAppTest"
            )
        )
    }
}
