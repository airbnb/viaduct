package viaduct.graphql.schema.binary.extensions

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.readBSchema
import viaduct.graphql.schema.binary.writeBSchema

/**
 * Reads a binary-encoded schema from the file.
 *
 * @param file The file containing the binary schema data
 * @return A ViaductSchema representation of the binary schema
 */
fun ViaductSchema.Companion.fromBinaryFile(file: File): ViaductSchema = FileInputStream(file).use { readBSchema(it) }

/**
 * Reads a binary-encoded schema from the input stream.
 *
 * @param input The input stream containing the binary schema data
 * @return A ViaductSchema representation of the binary schema
 */
fun ViaductSchema.Companion.fromBinaryFile(input: InputStream): ViaductSchema = readBSchema(input)

/**
 * Writes this schema to a binary file.
 *
 * @param file The file to write the binary schema to
 */
fun ViaductSchema.toBinaryFile(file: File) {
    FileOutputStream(file).use { writeBSchema(this, it) }
}

/**
 * Writes this schema to a binary output stream.
 *
 * @param output The output stream to write the binary schema to
 */
fun ViaductSchema.toBinaryFile(output: OutputStream) {
    writeBSchema(this, output)
}
