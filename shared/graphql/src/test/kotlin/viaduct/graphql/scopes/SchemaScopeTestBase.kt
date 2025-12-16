@file:Suppress("UnstableApiUsage")

package viaduct.graphql.scopes

import com.google.common.io.Resources
import graphql.parser.MultiSourceReader
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.InputStreamReader
import java.net.URL
import org.junit.jupiter.api.Assertions.assertEquals
import viaduct.graphql.utils.DefaultSchemaProvider

abstract class SchemaScopeTestBase {
    protected fun assertSchemaEqualToFixture(
        expectedPath: String,
        actualSchema: GraphQLSchema,
        includeScopeDirectives: Boolean = false,
        includeDirectiveDefinitions: Boolean = false
    ) {
        val actualSchemaPrinted = printSchema(actualSchema, includeScopeDirectives, includeDirectiveDefinitions)
        val expectedSchema = printSchema(readSchema(expectedPath), includeScopeDirectives, includeDirectiveDefinitions)

        assertEquals(expectedSchema, actualSchemaPrinted, "The schema does not match the expected fixture.")
    }

    protected fun readSchema(path: String): GraphQLSchema = toSchema(listOf(getResourceForPath(path)))

    protected fun readFixtureAsString(path: String) = getResourceForPath(path).readText()

    protected fun printSchema(
        schema: GraphQLSchema,
        includeScopeDirectives: Boolean = false,
        includeDirectiveDefinitions: Boolean = false,
        includeAstDefinitions: Boolean = false
    ): String =
        SchemaPrinter(
            SchemaPrinter.Options
                .defaultOptions()
                .includeDirectives {
                    if (it.startsWith("scope")) {
                        includeScopeDirectives
                    } else {
                        true
                    }
                }.includeDirectiveDefinitions(includeDirectiveDefinitions)
                .includeScalarTypes(false)
                .includeSchemaDefinition(true)
                .includeIntrospectionTypes(false)
                .useAstDefinitions(includeAstDefinitions)
        ).print(schema).let {
            // graphql-java has a typo in 18.1, which gradle uses
            // https://github.com/graphql-java/graphql-java/blob/v18.1/src/main/java/graphql/Directives.java#L85
            it.replace(
                "Directs the executor to skip this field or fragment when the `if`'argument is true.",
                "Directs the executor to skip this field or fragment when the `if` argument is true."
            )
        }

    private fun getResourceForPath(path: String) = Resources.getResource("fixtures/" + path.trimStart('/'))

    private fun toSchema(resources: List<URL>) =
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser()
                .parse(
                    readerForResources(resources)
                ).apply {
                    DefaultSchemaProvider.addDefaults(this, allowExisting = true)
                }
        )

    private fun readerForResources(files: List<URL>): MultiSourceReader {
        val multiSourceReader = MultiSourceReader.newMultiSourceReader().trackData(true)
        files.forEach {
            multiSourceReader.reader(InputStreamReader(it.openStream()), it.path)
        }
        return multiSourceReader.build()
    }
}
