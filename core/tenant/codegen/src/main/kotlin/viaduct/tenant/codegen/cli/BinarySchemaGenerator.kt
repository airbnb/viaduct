package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.toBinaryFile
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema

/**
 * CLI tool to serialize a list of schema files as binary viaduct schema
 */
class BinarySchemaGenerator : CliktCommand() {
    // Static entry point for Gradle Worker API classloader isolation (see CodegenWorkAction).
    object Main {
        @JvmStatic
        fun main(args: Array<String>) = BinarySchemaGenerator().main(args)
    }

    private val schemaFiles: List<File> by option("--schema_files")
        .file(mustExist = true, canBeDir = false).split(",").required()
    private val outputFile: File by option("--output_file")
        .file(mustExist = false, canBeDir = false).required()

    override fun run() {
        try {
            val schema = ViaductSchema.fromGraphQLSchema(schemaFiles)
            schema.toBinaryFile(outputFile)
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable
        ) {
            printSchemaDiagnostics()
            throw t
        }
    }

    private fun printSchemaDiagnostics() {
        println("BinarySchemaGenerator failed while parsing schema input.")
        println("Schema files in parse order:")
        schemaFiles.forEach { file ->
            println(" - ${file.path}")
        }
        println("Begin exact concatenated schema input:")
        schemaFiles.forEach { file ->
            print(file.readText())
        }
        println()
        println("End exact concatenated schema input")
    }
}
