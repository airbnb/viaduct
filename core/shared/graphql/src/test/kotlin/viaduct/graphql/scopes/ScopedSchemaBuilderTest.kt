package viaduct.graphql.scopes

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.graphqljava.viaductSchema

class ScopedSchemaBuilderTest {
    // GraphQL Java makes it difficult to inspect that GraphQLTypeReference's
    // have been resolved
    // In lieu of tools that can make assertions, these tests just assert that a schema can be transformed
    // without throwing an exception

    private val BOILERPLATE = """
        schema {
            query: Query
        }

        directive @scope(
          to: [String!]!
        ) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION | SCALAR

        type Query @scope(to:["*"]) {
            placeholder: Int
        }
    """

    @Test
    fun `ref-ifies types used in directive definitions`() {
        verify(
            """
            $BOILERPLATE

            enum MyEnum @scope(to:["*"]) {
                Foo
            }

            directive @dirWithEnum(x: MyEnum) on OBJECT
            """
        )
    }

    @Test
    fun `ref-ifies types used in directives applied to objects`() {
        verify(
            """
            $BOILERPLATE

            enum MyEnum @scope(to:["*"]) {
                Foo
            }

            directive @dirWithEnum(x: MyEnum) on OBJECT

            type MyObject @dirWithEnum(x: Foo) @scope(to: ["*"]) {
              placeholder: Int
            }

            extend type Query @scope(to: ["*"]) {
              obj: MyObject
              enm: MyEnum
            }
            """
        )
    }

    @Test
    fun `full view preserves directive definitions for schema conversion`() {
        val source = UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(
                """
                directive @serviceBackedNode(service: String!, backend: Backend = FIRST, ttl: Int = 42) repeatable on OBJECT
                enum Backend { FIRST SECOND }
                type Query { item: Item }
                type Item @serviceBackedNode(service: "first") { id: ID }
                extend type Item @serviceBackedNode(service: "second", backend: SECOND, ttl: 7) { name: String }
                """.trimIndent()
            )
        )
        val expected = source.viaductSchema()

        val transformed = ScopedSchemaBuilder(source, SchemaScopingMode.Unscoped, listOf())
            .build(SchemaView.Full)
            .filtered
        val actual = transformed.viaductSchema()

        assertEquals(
            source.getDirective("serviceBackedNode").definition,
            transformed.getDirective("serviceBackedNode").definition
        )
        assertEquals(
            expected.types.getValue("Item").extensions.map { it.appliedDirectives.map { directive -> directive.arguments } },
            actual.types.getValue("Item").extensions.map { it.appliedDirectives.map { directive -> directive.arguments } }
        )
    }

    private fun verify(
        sdl: String,
        validScopes: Set<String> = setOf("myscope"),
        scopesToApply: Set<String> = setOf("myscope")
    ) {
        ScopedSchemaBuilder(
            UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl)),
            SchemaScopingMode.ScopeAware(validScopes),
            listOf()
        ).build(SchemaView.Scoped(scopesToApply))
    }
}
