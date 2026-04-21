package viaduct.tenant.codegen.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.io.File

/**
 * Aggregation CLI that combines per-file KSP descriptors and schema files into
 * a single tenant module config file at `META-INF/viaduct/modules/<tenantpkg>.json`.
 *
 * The input descriptor directory is expected to contain the per-file JSON
 * descriptors emitted by the KSP extractor under `viaduct-registry/<package-path>/`.
 *
 * The output is a JSON envelope that embeds the raw descriptor JSON objects alongside
 * the schema file content. Full aggregation logic (parsing, enrichment, schema joining)
 * is deferred to a later step.
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

    private val outputDir: File by option("--output-dir")
        .file(mustExist = false, canBeFile = false)
        .required()

    override fun run() {
        val descriptors = descriptorDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.relativeTo(descriptorDir).path.replace(File.separatorChar, '/') }
            .toList()

        val outputFile = outputDir
            .resolve("META-INF/viaduct/modules")
            .resolve("$tenantPackage.json")

        outputFile.parentFile.mkdirs()

        val mapper = ObjectMapper()

        outputFile.bufferedWriter().use { writer ->
            writer.write("{\n")
            writer.write("  \"tenantPackage\": \"$tenantPackage\",\n")
            writer.write("  \"descriptorCount\": ${descriptors.size},\n")
            writer.write("  \"descriptors\": [\n")

            descriptors.forEachIndexed { index, file ->
                val relPath = file.relativeTo(descriptorDir).path.replace(File.separatorChar, '/')
                val content = file.readText().trim()
                writer.write("    { \"path\": ${mapper.writeValueAsString(relPath)}, \"content\": $content }")
                if (index < descriptors.size - 1) {
                    writer.write(",")
                }
                writer.write("\n")
            }

            writer.write("  ],\n")

            val escapedSchema = mapper.writeValueAsString(schemaFile.readText())
            writer.write("  \"schemaFile\": { \"name\": ${mapper.writeValueAsString(schemaFile.name)}, \"content\": $escapedSchema }\n")

            writer.write("}\n")
        }

        echo("Wrote ${outputFile.relativeTo(outputDir)} (${descriptors.size} descriptors, 1 schema file)")
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = AssembleTenantModuleConfigFile().main(args)
    }
}
