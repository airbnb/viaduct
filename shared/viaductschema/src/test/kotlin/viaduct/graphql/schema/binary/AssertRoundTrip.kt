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

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.checkBridgeSchemaInvariants
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.graphql.schema.test.BUILTIN_SCALARS
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.graphql.schema.test.mkSchemaWithSourceLocations
import viaduct.invariants.InvariantChecker

/** Alias for backward compatibility with existing tests in this package. */
internal val builtins = BUILTIN_SCALARS

internal fun checkRoundTrip(
    expectedSchema: ViaductSchema,
    checker: InvariantChecker
) {
    // Check invariants on input schema
    checkBridgeSchemaInvariants(expectedSchema, checker)

    // Turn into a BSchema file
    val tmp = ByteArrayOutputStream()
    writeBSchema(expectedSchema, tmp)

    val bfile = ByteArrayInputStream(tmp.toByteArray())
    val actual = readBSchema(bfile)

    // Check invariants on decoded schema
    checkBridgeSchemaInvariants(actual, checker)

    // Verify the schemas match structurally (reusing same checker)
    SchemaDiff(expectedSchema, actual, checker).diff()
}

/**
 * Core round-trip test: encode a schema to binary, decode it back,
 * and verify they match using SchemaDiff. Also validates that both
 * schemas satisfy all ViaductSchema invariants.
 */
internal fun assertRoundTrip(expectedSchema: ViaductSchema) {
    val checker = InvariantChecker()
    checkRoundTrip(expectedSchema, checker)
    checker.assertEmpty("\n")
}

/**
 * Convenience wrapper that parses SDL and calls the core assertRoundTrip.
 * The [extras] parameter deals with strange behavior of schema builder
 * when it comes to pre-defined scalars. If you call
 * [GraphQLSchema.Builder.additionalType] on a scalar that is
 * not "used", then you (sometimes) get a "can't define a
 * type twice" error (because graphql-java adds it). However, if
 * you "use" it without adding it, you'll get a missing type
 * error. I can't figure out the rule for when you do and don't
 * need to add these types yourself.
 */
internal fun assertRoundTrip(
    sdl: String,
    extras: String = BUILTIN_SCALARS
) {
    val fullSdl = "$extras\n$sdl"
    try {
        val tdr = SchemaParser().parse(fullSdl)
        val schema = GJSchemaRaw.fromRegistry(tdr)
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
        val schema = mkSchemaWithSourceLocations(sdlAndSourceNames, sdlWithNoLocation)
        assertRoundTrip(schema)
    } catch (e: Exception) {
        throw AssertionError("Failed to parse schema with source locations", e)
    }
}
