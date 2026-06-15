package viaduct.graphql.schema.test

import com.google.common.io.Resources
import graphql.parser.MultiSourceReader
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.graphqljava.readTypesFromURLs
import viaduct.utils.classgraph.findResourcePathsMatching

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

private val EXCLUDED_SCHEMA_MODULES = setOf("testfixtures", "data/codelab", "presentation/codelab")

fun createSchema(schema: String): ViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(MIN_SCHEMA + schema))

fun createGraphQLSchema(schema: String): GraphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(MIN_SCHEMA + schema))

fun loadGraphQLSchema(schemaResourcePaths: List<String>): ViaductSchema {
    require(schemaResourcePaths.isNotEmpty()) { "schemaResourcePaths must not be empty" }
    val paths = schemaResourcePaths.map { Resources.getResource(it) }
    return ViaductSchema.fromTypeDefinitionRegistry(readTypesFromURLs(paths))
}

fun loadGraphQLSchema(schemaResourcePath: String? = null): ViaductSchema {
    val packageWithSchema = System.getenv()["PACKAGE_WITH_SCHEMA"] ?: "graphql"
    val paths = findGraphQLSchemaResources(packageWithSchema, schemaResourcePath)

    if (paths.isEmpty()) {
        throw IllegalStateException("Could not find any graphqls files in the classpath ($packageWithSchema)")
    }

    return ViaductSchema.fromTypeDefinitionRegistry(readTypesFromURLs(paths))
}

fun findGraphQLSchemaResources(
    packageWithSchema: String,
    schemaResourcePath: String? = null
) = if (schemaResourcePath != null) {
    listOf(Resources.getResource(schemaResourcePath))
} else {
    findResourcePathsMatching(packageWithSchema, Regex(".*\\.graphqls"))
        .filter(::isIncludedSchemaResource)
        .map { resourcePath -> Resources.getResource(resourcePath) }
}

fun isIncludedSchemaResource(resourcePath: String): Boolean =
    EXCLUDED_SCHEMA_MODULES.none { schemaModuleDirectoryPath ->
        resourcePath.contains("graphql/$schemaModuleDirectoryPath")
    }

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
