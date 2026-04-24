package viaduct.tenant.codegen.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistry
import viaduct.engine.api.bootstrap.executionregistry.NodeAPIData
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.tenant.codegen.ksp.ResolverDescriptorFile

/**
 * Aggregation CLI that combines per-file KSP descriptors into a single
 * [ExecutionRegistry] JSON at `META-INF/viaduct/modules/<tenantpkg>.json`.
 *
 * Schema enrichment (isBatching, isSelective, return types) is intentionally
 * deferred — file-based bootstrapping separates executor construction from
 * schema validation, so schema is not needed here.
 *
 * Invoked via process-isolated Gradle worker ([CodegenWorkAction]) from the
 * Gradle assembly task.
 */
class AssembleTenantModuleConfigFile : CliktCommand(
    name = "assemble-tenant-module-config-file",
) {
    private val descriptorDir: File by option("--descriptor-dir")
        .file(mustExist = true, canBeFile = false)
        .required()

    private val schemaFile: File by option("--schema-file")
        .file(mustExist = true, canBeDir = false)
        .required()

    private val tenantPackage: String by option("--tenant-package")
        .required()

    private val executorFactory: String by option("--executor-factory").required()

    private val outputDir: File by option("--output-dir")
        .file(mustExist = false, canBeFile = false)
        .required()

    private val mapper = jacksonObjectMapper()

    override fun run() {
        val descriptorFiles = descriptorDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.relativeTo(descriptorDir).path.replace(File.separatorChar, '/') }
            .toList()

        val nodeEntries = descriptorFiles
            .flatMap { file -> mapper.readValue(file, ResolverDescriptorFile::class.java).nodes }
            .sortedBy { it.typeName }
            .map { node ->
                NodeEntry(
                    typeName = node.typeName,
                    isBatching = false,
                    isSelective = false,
                    attribution = node.typeName,
                    tenantAPIData = NodeAPIData(
                        resolverClass = node.implFqn,
                        resolverBaseClass = node.resolverBaseClass,
                    ),
                )
            }

        val registry = ExecutionRegistry(
            version = "1.0",
            executorFactory = executorFactory,
            nodes = nodeEntries,
        )

        val outputFile = outputDir
            .resolve("META-INF/viaduct/modules")
            .resolve("$tenantPackage.json")

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(registry)
        )

        echo("Wrote ${outputFile.relativeTo(outputDir)} (${nodeEntries.size} node(s))")
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = AssembleTenantModuleConfigFile().main(args)
    }
}
