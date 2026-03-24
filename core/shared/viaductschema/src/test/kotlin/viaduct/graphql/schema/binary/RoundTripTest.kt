package viaduct.graphql.schema.binary

import com.google.common.io.Resources
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.SchemaSize
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

/**
 * Round-trip tests for large schemas.
 *
 * These tests verify that large schemas can be:
 * 1. Parsed from SDL text into GJSchemaRaw
 * 2. Written to binary format
 * 3. Read back from binary format
 * 4. Compared successfully against the original
 */
class RoundTripTest {
    /**
     * Round-trip test using a 5,000-type randomly generated schema.
     *
     * Uses the Arb schema generator with a fixed seed for reproducibility.
     * We write to a temp file and parse from there to match how the benchmark works.
     */
    @Test
    fun `round trip with 5000-type Arb schema`() {
        // Generate a test schema using Arb with 5000 types
        val cfg = Config.default + (SchemaSize to 5000)
        val rs = RandomSource.seeded(42L) // Use fixed seed for reproducibility
        val graphQLSchema = Arb.graphQLSchema(cfg).next(rs)

        // Print the schema to SDL text
        val schemaSdl = graphql.schema.idl.SchemaPrinter().print(graphQLSchema)

        // Write to temp file and parse with GJSchemaRaw.fromFiles
        val tempFile = File.createTempFile("arb-schema", ".graphqls")
        try {
            tempFile.writeText(schemaSdl)
            val registry = graphql.schema.idl.SchemaParser().parse(tempFile)
            val gjSchema = ViaductSchema.fromTypeDefinitionRegistry(registry)

            // Run the round-trip test
            assertRoundTrip(gjSchema)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Round-trip test using the large-schema-4 test fixture.
     *
     * This schema has ~18,000 types and 50,588 unique string constants.
     */
    @Disabled("Requires too much heap for Gradle test runner")
    @Test
    fun `round trip with large-schema-4`() {
        // Load the zipped schema from resources and unzip to temp file
        val resourcePath = "large-schema-4/large-schema-4.graphqls.zip"
        val zipResource = Resources.getResource(resourcePath)

        val tempFile = File.createTempFile("large-schema-4", ".graphqls")
        try {
            // Unzip the schema to temp file
            ZipInputStream(zipResource.openStream()).use { zipStream ->
                zipStream.nextEntry
                tempFile.outputStream().use { output ->
                    zipStream.copyTo(output)
                }
            }

            // Parse from temp file
            val registry = graphql.schema.idl.SchemaParser().parse(tempFile)
            val gjSchema = ViaductSchema.fromTypeDefinitionRegistry(registry)

            // Run the round-trip test
            assertRoundTrip(gjSchema)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Round-trip test using the large-schema-5 test fixture.
     *
     * This is an anonymized version of the central schema.
     */
    @Disabled("Requires too much heap for Gradle test runner")
    @Test
    fun `round trip with large-schema-5`() {
        // Load the zipped schema from resources and unzip to temp file
        val resourcePath = "large-schema-5/large-schema-5.graphqls.zip"
        val zipResource = Resources.getResource(resourcePath)

        val tempFile = File.createTempFile("large-schema-5", ".graphqls")
        try {
            // Unzip the schema to temp file
            ZipInputStream(zipResource.openStream()).use { zipStream ->
                zipStream.nextEntry
                tempFile.outputStream().use { output ->
                    zipStream.copyTo(output)
                }
            }

            // Parse from temp file
            val registry = graphql.schema.idl.SchemaParser().parse(tempFile)
            val gjSchema = ViaductSchema.fromTypeDefinitionRegistry(registry)

            // Run the round-trip test
            assertRoundTrip(gjSchema)
        } finally {
            tempFile.delete()
        }
    }
}
