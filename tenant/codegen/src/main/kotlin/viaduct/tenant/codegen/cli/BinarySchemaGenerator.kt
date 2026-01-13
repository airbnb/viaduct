package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.io.FileOutputStream
import viaduct.graphql.schema.binary.writeBSchema
import viaduct.graphql.schema.graphqljava.GJSchema

/**
 * CLI tool to serialize a list of schema files as binary viaduct schema
 */
class BinarySchemaGenerator : CliktCommand() {
    private val schemaFiles: List<File> by option("--schema_files")
        .file(mustExist = true, canBeDir = false).split(",").required()
    private val outputFile: File by option("--output_file")
        .file(mustExist = false, canBeDir = false).required()

    override fun run() {
        val schema = GJSchema.fromFiles(schemaFiles)
        writeBSchema(schema, FileOutputStream(outputFile))
    }
}
