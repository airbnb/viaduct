package viaduct.graphql.schema

import com.google.common.io.Resources
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaPrinter
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import viaduct.graphql.schema.binary.extensions.toBinaryFile
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.graphqljava.readTypesFromURLs

/**
 * Data class holding prepared benchmark data for a large schema.
 */
data class LargeSchemaBenchmarkData(
    val textSchemaFile: File,
    val binarySchemaFile: File,
    val schemaSdl: String,
    val binarySchemaBytes: ByteArray
) {
    fun cleanup() {
        textSchemaFile.delete()
        binarySchemaFile.delete()
    }
}

/**
 * Helper functions shared between LargeSchemaBenchmark and LargeSchema5Benchmark.
 */
object LargeSchemaBenchmarkHelper {
    /**
     * Load a zipped schema from resources and return the GraphQLSchema.
     *
     * @param version The schema version (e.g., "4" or "5")
     * @return The loaded GraphQLSchema, or null if not found
     */
    fun loadZippedSchema(version: String): GraphQLSchema? {
        return try {
            val resourcePath = "large-schema-$version/large-schema-$version.graphqls.zip"
            val zipResource = Resources.getResource(resourcePath)

            println("Found zipped schema at: $resourcePath")

            // Unzip to temp file
            val tempFile = File.createTempFile("large-schema-$version", ".graphqls")
            try {
                ZipInputStream(zipResource.openStream()).use { zipStream ->
                    zipStream.nextEntry
                    tempFile.outputStream().use { output ->
                        zipStream.copyTo(output)
                    }
                }

                println("Unzipped schema to: ${tempFile.absolutePath}")

                val registry = readTypesFromURLs(listOf(tempFile.toURI().toURL()))
                UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            println("Error loading schema: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Prepare benchmark data from a GraphQLSchema.
     *
     * @param schema The GraphQLSchema to prepare
     * @param schemaName A name for logging (e.g., "large-schema-4")
     * @return The prepared benchmark data
     */
    fun prepareBenchmarkData(
        schema: GraphQLSchema,
        schemaName: String
    ): LargeSchemaBenchmarkData {
        // Print the schema to SDL text
        val schemaSdl = SchemaPrinter().print(schema)

        // Create temporary files
        val textSchemaFile = File.createTempFile(schemaName, ".graphqls")
        val binarySchemaFile = File.createTempFile(schemaName, ".bschema")

        // Write text schema to file
        textSchemaFile.writeText(schemaSdl)

        // Parse the text schema to get a ViaductSchema, then write binary version
        val viaductSchema = ViaductSchema.fromTypeDefinitionRegistry(listOf(textSchemaFile))
        val binaryOut = ByteArrayOutputStream()
        viaductSchema.toBinaryFile(binaryOut)
        val binarySchemaBytes = binaryOut.toByteArray()
        binarySchemaFile.writeBytes(binarySchemaBytes)

        // Also write to fixed locations for analysis
        File("/tmp/$schemaName.graphqls").writeText(schemaSdl)
        File("/tmp/$schemaName.bschema").writeBytes(binarySchemaBytes)

        println("Setup complete ($schemaName):")
        println("  Text schema size: ${schemaSdl.length} bytes (${schemaSdl.length / 1024} KB)")
        println("  Binary schema size: ${binarySchemaBytes.size} bytes (${binarySchemaBytes.size / 1024} KB)")
        println("  Compression ratio: ${"%.2f".format(binarySchemaBytes.size.toDouble() / schemaSdl.length)}")
        println("  Files written to: /tmp/$schemaName.graphqls and /tmp/$schemaName.bschema")

        return LargeSchemaBenchmarkData(
            textSchemaFile = textSchemaFile,
            binarySchemaFile = binarySchemaFile,
            schemaSdl = schemaSdl,
            binarySchemaBytes = binarySchemaBytes
        )
    }
}
