package viaduct.graphql.schema.test

import com.google.common.io.Resources
import graphql.parser.MultiSourceReader
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
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

fun mkSchema(schema: String): ViaductSchema = GJSchemaRaw.fromRegistry(SchemaParser().parse(MIN_SCHEMA + schema))

fun mkGraphQLSchema(schema: String): GraphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(MIN_SCHEMA + schema))

fun loadGraphQLSchema(schemaResourcePath: String? = null): ViaductSchema {
    val packageWithSchema = System.getenv()["PACKAGE_WITH_SCHEMA"] ?: "graphql"
    val paths = if (schemaResourcePath != null) {
        listOf(Resources.getResource(schemaResourcePath))
    } else {
        // scan all the graphqls files in the classloader and load them as the schema
        val reflections = Reflections(
            ConfigurationBuilder()
                .setUrls(
                    ClasspathHelper.forPackage(
                        packageWithSchema,
                        ClasspathHelper.contextClassLoader(),
                        ClasspathHelper.staticClassLoader(),
                    ),
                ).addScanners(Scanners.Resources),
        )
        val graphqlsResources = Scanners.Resources.with(".*\\.graphqls")

        // Note: excluded schema modules are hard coded here to avoid pulling in tools/viaduct into oss
        // For the list of excluded modules, vist the link below
        // https://sourcegraph.a.musta.ch/airbnb/treehouse@8c0a0ea334b1556a40a40bcf725ff154668c2299/-/blob/tools/viaduct/src/main/kotlin/com/airbnb/viaduct/schema/modules/SchemaModule.kt?L106
        val excludedSchemaModules = setOf("testfixtures", "data/codelab", "presentation/codelab")
        reflections
            .get(graphqlsResources)
            .filter { resourcePath ->
                excludedSchemaModules.none { schemaModuleDirectoryPath ->
                    resourcePath.contains("graphql/$schemaModuleDirectoryPath")
                }
            }.map { Resources.getResource(it) }
    }

    if (paths.isEmpty()) {
        throw IllegalStateException("Could not find any graphqls files in the classpath ($packageWithSchema)")
    }

    return GJSchemaRaw.fromRegistry(readTypesFromURLs(paths))
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
fun mkSchemaWithSourceLocations(
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

    return GJSchemaRaw.fromRegistry(finalTdr)
}

/**
 * Convenience overload to create a schema with a single source location.
 *
 * @param sdl The SDL to parse
 * @param sourceName The source name to associate with all types/fields
 * @param sdlWithNoLocation Optional SDL to parse without source location and merge in
 * @return A ViaductSchema with source locations populated
 */
fun mkSchemaWithSourceLocation(
    sdl: String,
    sourceName: String,
    sdlWithNoLocation: String? = null
): ViaductSchema = mkSchemaWithSourceLocations(listOf(sdl to sourceName), sdlWithNoLocation)
