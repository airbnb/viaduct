package viaduct.gradle.featureappcontract

import java.io.File
import org.gradle.api.tasks.CacheableTask
import viaduct.gradle.common.CodegenWorkAction
import viaduct.gradle.common.runCodegen

/**
 * Java contract codegen task.
 *
 * For each contract schema, generates:
 * 1. Java GRT source files via `JAVA_GRTS_GENERATOR` (GRT mode) — creates package subdirs
 * 2. Java resolver base source files via `JAVA_GRTS_GENERATOR` (resolver mode) — writes flat
 *
 * The CLI generates both GRTs and resolvers in a single invocation, so we invoke it twice:
 * once keeping GRTs (discarding resolvers), once keeping resolvers (discarding GRTs).
 */
@CacheableTask
abstract class ViaductJavaContractCodegenTask : ViaductContractCodegenTaskBase() {
    override fun generateForSchema(
        schemaFile: File,
        pkg: String,
        grtDir: File,
        tenantDir: File
    ) {
        val allSchemaFiles = listOf(schemaFile, defaultSchemaFile.get().asFile)
        val schemaFilesArg = allSchemaFiles.joinToString(",") { it.absolutePath }

        // Invocation 1: Generate Java GRTs (keep GRTs, discard resolvers)
        val resolverDiscardDir = temporaryDir.resolve("discard-resolvers")
        resolverDiscardDir.deleteRecursively()
        resolverDiscardDir.mkdirs()

        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.JAVA_GRTS_GENERATOR,
            listOf(
                "--schema_files",
                schemaFilesArg,
                "--grt_output_dir",
                grtDir.absolutePath,
                "--grt_package",
                pkg,
                "--resolver_generated_dir",
                resolverDiscardDir.absolutePath,
                "--tenant_package",
                pkg,
                "--include_root_types"
            )
        )

        // Invocation 2: Generate Java resolver bases (keep resolvers, discard GRTs)
        val grtDiscardDir = temporaryDir.resolve("discard-grts")
        grtDiscardDir.deleteRecursively()
        grtDiscardDir.mkdirs()

        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.JAVA_GRTS_GENERATOR,
            listOf(
                "--schema_files",
                schemaFilesArg,
                "--grt_output_dir",
                grtDiscardDir.absolutePath,
                "--grt_package",
                pkg,
                "--resolver_generated_dir",
                tenantDir.absolutePath,
                "--tenant_package",
                pkg
            )
        )
    }
}
