package viaduct.graphql.schema.test

import com.google.common.io.Resources
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

fun loadGraphQLSchema(): ViaductSchema {
    // scan all the graphqls files in the classloader and load them as the schema
    val packageWithSchema = System.getenv()["PACKAGE_WITH_SCHEMA"] ?: "graphql"
    val reflections = Reflections(
        ConfigurationBuilder()
            .setUrls(
                ClasspathHelper.forPackage(
                    packageWithSchema,
                    ClasspathHelper.contextClassLoader(),
                    ClasspathHelper.staticClassLoader()
                )
            )
            .addScanners(Scanners.Resources)
    )
    val graphqlsResources = Scanners.Resources.with(".*\\.graphqls")

    // Note: excluded schema modules are hard coded here to avoid pulling in tools/viaduct into oss
    // For the list of excluded modules, vist the link below
    // https://sourcegraph.a.musta.ch/airbnb/treehouse@8c0a0ea334b1556a40a40bcf725ff154668c2299/-/blob/tools/viaduct/src/main/kotlin/com/airbnb/viaduct/schema/modules/SchemaModule.kt?L106
    val excludedSchemaModules = setOf("testfixtures", "data/codelab", "presentation/codelab")
    val paths = reflections.get(graphqlsResources)
        .filter { resourcePath ->
            excludedSchemaModules.none { schemaModuleDirectoryPath -> resourcePath.contains("graphql/$schemaModuleDirectoryPath") }
        }.map { Resources.getResource(it) }

    if (paths.isEmpty()) {
        throw IllegalStateException("Could not find any graphqls files in the classpath ($packageWithSchema)")
    }

    return GJSchemaRaw.fromRegistry(readTypesFromURLs(paths))
}
