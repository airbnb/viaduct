/**
 * "Round trip" testing means we take a [GJSchemaRaw], write it
 * out (in memory) using the [BSchema] format, then read that
 * back and compare the two schemas using [SchemaDiff].
 *
 *
 *
 * @file
 */
package viaduct.graphql.schema.binary

import graphql.schema.idl.SchemaParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.fromBinaryFile
import viaduct.graphql.schema.binary.extensions.toBinaryFile
import viaduct.graphql.schema.checkViaductSchemaInvariants
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.test.BUILTIN_SCALARS
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.createSchemaWithSourceLocations
import viaduct.invariants.FailureCollector

/** Alias for backward compatibility with existing tests in this package. */
internal val builtins = BUILTIN_SCALARS

internal fun checkRoundTrip(
    expectedSchema: ViaductSchema,
    checker: FailureCollector
) {
    // Check invariants on input schema
    checkViaductSchemaInvariants(expectedSchema, checker)

    // Turn into a BSchema file
    val tmp = ByteArrayOutputStream()
    expectedSchema.toBinaryFile(tmp)

    val bfile = ByteArrayInputStream(tmp.toByteArray())
    val actual = ViaductSchema.fromBinaryFile(bfile)

    // Check invariants on decoded schema
    checkViaductSchemaInvariants(actual, checker)

    // Verify the schemas match structurally (reusing same checker)
    SchemaDiff(expectedSchema, actual, checker).diff()
}

/**
 * Core round-trip test: encode a schema to binary, decode it back,
 * and verify they match using SchemaDiff. Also validates that both
 * schemas satisfy all ViaductSchema invariants.
 */
internal fun assertRoundTrip(expectedSchema: ViaductSchema) {
    val checker = FailureCollector()
    checkRoundTrip(expectedSchema, checker)
    checker.assertEmpty("\n")
}

/**
 * Convenience wrapper that parses SDL and calls the core assertRoundTrip.
 *
 * @param sdl The schema SDL to test
 * @param extras Optional SDL to prepend (e.g., built-in scalars). Pass empty string for no extras.
 *               When not specified, no extras are added - the SDL is assumed to be complete.
 */
internal fun assertRoundTrip(
    sdl: String,
    extras: String = ""
) {
    val fullSdl = if (extras.isEmpty()) sdl else "$extras\n$sdl"
    try {
        val tdr = SchemaParser().parse(fullSdl)
        val schema = ViaductSchema.fromTypeDefinitionRegistry(tdr)
        assertRoundTrip(schema)
    } catch (e: Exception) {
        throw AssertionError("$fullSdl", e)
    }
}

/**
 * Test round-trip with explicit source locations using MultiSourceReader.
 *
 * @param sdlAndSourceNames List of (SDL, sourceName) pairs to parse with source locations
 * @param sdlWithNoLocation Optional SDL to parse without source location and merge in
 */
internal fun assertRoundTrip(
    sdlAndSourceNames: List<Pair<String, String>>,
    sdlWithNoLocation: String? = null
) {
    try {
        val schema = createSchemaWithSourceLocations(sdlAndSourceNames, sdlWithNoLocation)
        assertRoundTrip(schema)
    } catch (e: Exception) {
        throw AssertionError("Failed to parse schema with source locations", e)
    }
}
