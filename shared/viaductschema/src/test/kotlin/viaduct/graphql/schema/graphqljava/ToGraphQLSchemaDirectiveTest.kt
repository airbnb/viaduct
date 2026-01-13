package viaduct.graphql.schema.graphqljava

import graphql.schema.GraphQLObjectType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.test.SchemaDiff

/**
 * Tests for complex directive scenarios in ToGraphQLSchema conversion.
 *
 * Basic directive definitions, locations, and deprecation are covered by GJRoundTripTest.
 * These tests focus on more complex combinations not covered elsewhere.
 */
class ToGraphQLSchemaDirectiveTest {
    @Test
    fun `multiple directives with various argument types`() {
        val sdl = """
            enum Level { LOW, HIGH }
            input Config { enabled: Boolean! }

            directive @auth(roles: [String!]!) on FIELD_DEFINITION
            directive @cache(maxAge: Int!, scope: String = "public") on FIELD_DEFINITION
            directive @priority(level: Level!) on FIELD_DEFINITION
            directive @config(settings: Config!) on FIELD_DEFINITION

            type Query {
                data: String
                    @auth(roles: ["admin", "user"])
                    @cache(maxAge: 300)
                    @priority(level: HIGH)
                    @config(settings: { enabled: true })
            }
        """.trimIndent()
        verifyRoundTrip(sdl, setOf("Int"))
    }

    @Test
    fun `directives on interface and implementing type`() {
        val sdl = """
            directive @tracked on INTERFACE | OBJECT | FIELD_DEFINITION

            interface Entity @tracked {
                id: ID! @tracked
            }

            type User implements Entity @tracked {
                id: ID! @tracked
                name: String @tracked
            }

            type Query { user: User }
        """.trimIndent()
        verifyRoundTrip(sdl, setOf("ID"))
    }

    @Test
    fun `repeatable directive applied multiple times`() {
        val sdl = """
            directive @tag(name: String!) repeatable on OBJECT
            type Foo @tag(name: "a") @tag(name: "b") @tag(name: "c") { value: String }
            type Query { foo: Foo }
        """.trimIndent()
        verifyRoundTrip(sdl)
        val schema = parseAndConvert(sdl)
        val fooType = schema.getType("Foo") as GraphQLObjectType
        val tagDirectives = fooType.appliedDirectives.filter { it.name == "tag" }
        tagDirectives shouldHaveSize 3
    }

    @Test
    fun `applied directive with input object argument on field`() {
        val sdl = """
            input Config { enabled: Boolean!, name: String }
            directive @configure(config: Config!) on FIELD_DEFINITION
            type Query { foo: String @configure(config: { enabled: true, name: "test" }) }
        """.trimIndent()
        verifyRoundTrip(sdl)
    }

    @Test
    fun `deprecated field with reason preserved`() {
        val sdl = """
            type Query { foo: String @deprecated(reason: "Use bar instead") }
        """.trimIndent()
        verifyRoundTrip(sdl)
        val schema = parseAndConvert(sdl)
        val field = schema.queryType.getFieldDefinition("foo")
        field.isDeprecated shouldBe true
        field.deprecationReason shouldBe "Use bar instead"
    }

    companion object {
        private fun verifyRoundTrip(
            sdl: String,
            scalarsNeeded: Set<String> = emptySet(),
        ) {
            val tdr = SchemaParser().parse(sdl)
            val expectedGraphQLSchema = SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
            val expectedViaductSchema = GJSchema.fromSchema(expectedGraphQLSchema)

            val actualGraphQLSchema = expectedViaductSchema.toGraphQLSchema(scalarsNeeded)
            val actualViaductSchema = GJSchema.fromSchema(actualGraphQLSchema)

            val schemaDiffResult = SchemaDiff(expectedViaductSchema, actualViaductSchema).diff()
            schemaDiffResult.assertEmpty()

            val extraDiffResult = GraphQLSchemaExtraDiff(expectedGraphQLSchema, actualGraphQLSchema).diff()
            extraDiffResult.assertEmpty()
        }

        private fun parseAndConvert(
            sdl: String,
            scalarsNeeded: Set<String> = emptySet(),
        ): graphql.schema.GraphQLSchema {
            val tdr = SchemaParser().parse(sdl)
            val originalSchema = SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
            val viaductSchema = GJSchema.fromSchema(originalSchema)
            return viaductSchema.toGraphQLSchema(scalarsNeeded)
        }
    }
}
