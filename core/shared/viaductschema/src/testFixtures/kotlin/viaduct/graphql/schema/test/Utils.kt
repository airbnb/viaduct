package viaduct.graphql.schema.test

import com.google.common.io.Resources
import graphql.parser.MultiSourceReader
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.fromBinaryFile
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.graphqljava.readTypesFromURLs

private val MIN_SCHEMA: String = """
    schema {
      query: Query
      mutation: Mutation
    }
    type Query { nop: Int }
    type Mutation { nop: Int }
    scalar Long
    scalar Short

""".trimIndent()

fun createSchema(schema: String): ViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(MIN_SCHEMA + schema))

fun createGraphQLSchema(schema: String): GraphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(MIN_SCHEMA + schema))

fun loadGraphQLSchema(schemaResourcePaths: List<String>): ViaductSchema {
    require(schemaResourcePaths.isNotEmpty()) { "schemaResourcePaths must not be empty" }
    val paths = schemaResourcePaths.map { Resources.getResource(it) }
    return ViaductSchema.fromTypeDefinitionRegistry(readTypesFromURLs(paths))
}

fun loadBinaryGraphQLSchema(schemaResourcePath: String): ViaductSchema = Resources.getResource(schemaResourcePath).openStream().use { ViaductSchema.fromBinaryFile(it, readDescriptions = true) }

/**
 * Built-in scalar definitions for use in tests that parse raw SDL.
 */
val BUILTIN_SCALARS: String =
    """
        scalar Boolean
        scalar Float
        scalar ID
        scalar Int
        scalar String

    """.trimIndent()

/**
 * Creates a [ViaductSchema] from SDL with explicit source locations.
 *
 * Each pair in [sdlAndSourceNames] is a (SDL, sourceName) pair. The sourceName
 * will be set as the source location on all types and fields defined in that SDL.
 *
 * @param sdlAndSourceNames List of (SDL, sourceName) pairs to parse with source locations
 * @param sdlWithNoLocation Optional SDL to parse without source location and merge in
 * @return A ViaductSchema with source locations populated
 */
fun createSchemaWithSourceLocations(
    sdlAndSourceNames: List<Pair<String, String>>,
    sdlWithNoLocation: String? = null
): ViaductSchema {
    // Build a MultiSourceReader with all the SDL fragments that have source names
    val builder = MultiSourceReader.newMultiSourceReader()
    for ((sdl, sourceName) in sdlAndSourceNames) {
        // Ensure each SDL fragment ends with a newline to avoid concatenation issues
        val sdlWithNewline = if (sdl.endsWith("\n")) sdl else "$sdl\n"
        builder.string(sdlWithNewline, sourceName)
    }
    val multiSourceReader = builder.build()

    // Parse the SDL with source locations
    val tdr = SchemaParser().parse(multiSourceReader)

    // If there's SDL without source location, parse and merge it
    val finalTdr = if (sdlWithNoLocation != null) {
        val tdrWithoutLocation = SchemaParser().parse(sdlWithNoLocation)
        tdr.merge(tdrWithoutLocation)
    } else {
        tdr
    }

    return ViaductSchema.fromTypeDefinitionRegistry(finalTdr)
}

/**
 * Convenience overload to create a schema with a single source location.
 *
 * @param sdl The SDL to parse
 * @param sourceName The source name to associate with all types/fields
 * @param sdlWithNoLocation Optional SDL to parse without source location and merge in
 * @return A ViaductSchema with source locations populated
 */
fun createSchemaWithSourceLocation(
    sdl: String,
    sourceName: String,
    sdlWithNoLocation: String? = null
): ViaductSchema = createSchemaWithSourceLocations(listOf(sdl to sourceName), sdlWithNoLocation)
