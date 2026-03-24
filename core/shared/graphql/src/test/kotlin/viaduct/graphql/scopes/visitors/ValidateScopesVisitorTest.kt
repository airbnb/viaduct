package viaduct.graphql.scopes.visitors

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.scopes.errors.DirectiveRetainedTypeScopeError
import viaduct.graphql.scopes.utils.ScopeDirectiveParser
import viaduct.graphql.scopes.utils.StubRoot
import viaduct.graphql.scopes.utils.buildSchemaTraverser

class ValidateScopesVisitorTest {
    private val BOILERPLATE = """
        schema {
            query: Query
        }

        directive @scope(
          to: [String!]!
        ) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION | SCALAR
    """

    @Test
    fun `rejects scoped input types that are used by a directive`() {
        assertThrows(
            """
            $BOILERPLATE

            input MyInput @scope(to: ["myscope"]) {
              foo: Int
            }

            directive @dirWithInput(x: MyInput) on OBJECT

            type Query @dirWithInput(x: {foo: 42}) {
                placeholder: Int
            }
            """
        )
    }

    @Test
    fun `rejects scoped enum types that are used by a directive`() {
        assertThrows(
            """
            $BOILERPLATE

            enum MyEnum @scope(to: ["myscope"]) {
              Foo
            }

            directive @dirWithEnum(x: MyEnum) on OBJECT

            type Query @dirWithEnum(x: Foo) {
                placeholder: Int
            }
            """
        )
    }

    @Test
    fun `allows wildcard-scoped types used by a directive`() {
        assertPasses(
            """
            $BOILERPLATE

            scalar MyScalar @scope(to:["*"])

            enum MyEnum @scope(to: ["*"]) {
              Foo
            }

            input MyInput @scope(to: ["*"]) {
              foo: Int
            }

            directive @dirWithScalar(x: MyScalar) on OBJECT
            directive @dirWithEnum(x: MyEnum) on OBJECT
            directive @dirWithInput(x: MyInput) on OBJECT

            type Query
                @dirWithScalar(x: 4)
                @dirWithEnum(x: Foo)
                @dirWithInput(x: {foo: 42})
            {
                placeholder: Int
            }
        """
        )
    }

    private fun run(sdl: String) {
        try {
            val schema = toSchema(sdl)
            val validScopes = setOf("myscope", "otherscope")
            val visitor = ValidateScopesVisitor(validScopes, ScopeDirectiveParser(validScopes))
            val traverser = buildSchemaTraverser(schema)
            traverser.traverse(StubRoot(schema), visitor)
        } catch (err: Exception) {
            Assertions.fail(err.message)
        }
    }

    private fun assertPasses(sdl: String) {
        run(sdl)
    }

    private fun assertThrows(sdl: String) {
        assertThrows<DirectiveRetainedTypeScopeError> {
            run(sdl)
        }
    }
}
