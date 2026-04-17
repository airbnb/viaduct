package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.io.File

/**
 * Aggregation CLI that combines per-file KSP descriptors and schema files into
 * a single tenant module config file at `META-INF/viaduct/modules/<tenantpkg>.json`.
 *
 * Currently a stub: concatenates all descriptor JSON files followed by the schema
 * file contents into the output. This will be replaced with real aggregation logic
 * that parses descriptors, enriches with schema info, and produces the execution
 * registry JSON.
 *
 * Invoked via process-isolated Gradle worker ([CodegenWorkAction]) from
 * [AssembleTenantModuleConfigFileTask].
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

    private val outputDir: File by option("--output-dir")
        .file(mustExist = false, canBeFile = false)
        .required()

    override fun run() {
        val descriptors = descriptorDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.relativeTo(descriptorDir).path }
            .toList()

        val outputFile = outputDir
            .resolve("META-INF/viaduct/modules")
            .resolve("$tenantPackage.json")

        outputFile.parentFile.mkdirs()

        outputFile.bufferedWriter().use { writer ->
            writer.write("{\n")
            writer.write("  \"tenantPackage\": \"$tenantPackage\",\n")
            writer.write("  \"descriptorCount\": ${descriptors.size},\n")
            writer.write("  \"descriptors\": [\n")

            descriptors.forEachIndexed { index, file ->
                val relPath = file.relativeTo(descriptorDir).path
                val content = file.readText().trim()
                writer.write("    { \"path\": \"$relPath\", \"content\": $content }")
                if (index < descriptors.size - 1) writer.write(",")
                writer.write("\n")
            }

            writer.write("  ],\n")

            val escaped = schemaFile.readText()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            writer.write("  \"schemaFile\": { \"name\": \"${schemaFile.name}\", \"content\": \"$escaped\" }\n")

            writer.write("}\n")
        }

        echo("Wrote ${outputFile.relativeTo(outputDir)} (${descriptors.size} descriptors, 1 schema file)")
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = AssembleTenantModuleConfigFile().main(args)
    }
}
