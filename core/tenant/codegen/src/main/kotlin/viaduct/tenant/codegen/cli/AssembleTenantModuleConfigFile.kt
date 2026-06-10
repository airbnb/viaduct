@file:Suppress("DEPRECATION") // Jackson configure() deprecated in newer versions

package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.io.File

/**
 * Aggregation CLI that combines per-file KSP descriptors into a single tenant module
 * config file at `META-INF/viaduct/modules/<tenantpkg>.json`.
 *
 * Deserializes each per-file [ResolverDescriptorFile], maps them to a typed [ExecutionRegistry],
 * then serializes that — so the JSON shape is always governed by the real data model and a
 * schema change in [ExecutionRegistry] becomes a build error here, not a runtime surprise.
 *
 * Invoked via process-isolated Gradle worker from the Gradle assembly task.
 */
class AssembleTenantModuleConfigFile : CliktCommand(
    name = "assemble-tenant-module-config-file",
) {
    private val descriptorDir: File by option("--descriptor-dir")
        .file(mustExist = true, canBeFile = false)
        .required()

    private val tenantPackage: String by option("--tenant-package")
        .required()

    private val executorFactory: String by option("--executor-factory")
        .default("")

    private val schemaSdl: File? by option("--schema-sdl")
        .file(mustExist = true, canBeFile = true)

    private val outputDir: File by option("--output-dir")
        .file(mustExist = false, canBeFile = false)
        .required()

    override fun run() {
        descriptorDir.walkTopDown()
            .filter { it.isFile && it.extension != "json" }
            .forEach { echo("WARNING: unexpected file in descriptor dir (not a .json): ${it.name}", err = true) }

        val descriptorJsons = descriptorDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.relativeTo(descriptorDir).path.replace(File.separatorChar, '/') }
            .map(File::readText)
            .toList()
        TenantModuleConfigAssembler.writeRegistry(
            descriptorJsons = descriptorJsons,
            executorFactory = executorFactory,
            tenantPackage = tenantPackage,
            schemaSdl = schemaSdl?.readText(),
            outputDir = outputDir,
        )
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = AssembleTenantModuleConfigFile().main(args)
    }
}
