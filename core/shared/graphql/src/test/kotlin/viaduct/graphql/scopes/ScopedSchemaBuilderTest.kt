package viaduct.graphql.scopes

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Test

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
    fun `ref-ifies types used in directive definitions`() =
        verify(
            """
            $BOILERPLATE

            enum MyEnum @scope(to:["*"]) {
                Foo
            }

            directive @dirWithEnum(x: MyEnum) on OBJECT
            """
        )

    @Test
    fun `ref-ifies types used in directives applied to objects`() =
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

    private fun verify(
        sdl: String,
        validScopes: Set<String> = setOf("myscope"),
        scopesToApply: Set<String> = setOf("myscope")
    ) {
        ScopedSchemaBuilder(
            UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl)),
            validScopes.toSortedSet(),
            listOf()
        ).applyScopes(scopesToApply)
    }
}
