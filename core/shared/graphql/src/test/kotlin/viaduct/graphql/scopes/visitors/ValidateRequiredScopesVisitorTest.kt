package viaduct.graphql.scopes.visitors

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.scopes.errors.SchemaScopeValidationError
import viaduct.graphql.scopes.utils.ScopeDirectiveParser
import viaduct.graphql.scopes.utils.StubRoot
import viaduct.graphql.scopes.utils.buildSchemaTraverser

class ValidateRequiredScopesVisitorTest {
    @Test
    fun `rejects non scoped queries`() {
        assertThrows<SchemaScopeValidationError> {
            run(
                """
                directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

                type Query {
                    hello: String
                }
                """
            )
        }
    }

    @Test
    fun `correct scoped queries should pass`() {
        run(
            """
            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

            type Query @scope(to: ["publicScope"]) {
                scopedQuery: String
            }

            type Mutation @scope(to: ["publicScope"]) {
              scopedMutation: String @deprecated
            }

            type Subscription @scope(to: ["publicScope"]) {
              scopedSubscription: String @deprecated
            }

            type AType {
              id: String
            }
            """
        )
    }

    @Test
    fun `wildcard scoped queries should pass`() {
        run(
            """
            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

            type Query @scope(to: ["*"]) {
                scopedQuery: String
            }

            type Mutation @scope(to: ["*"]) {
              scopedMutation: String @deprecated
            }

            type Subscription @scope(to: ["*"]) {
              scopedSubscription: String @deprecated
            }

            type AType {
              id: String
            }
            """
        )
    }

    private fun run(sdl: String) {
        try {
            val schema = toSchema(sdl)
            val validScopes = setOf("publicScope")
            val visitor = ValidateRequiredScopesVisitor(ScopeDirectiveParser(validScopes))
            val traverser = buildSchemaTraverser(schema)
            traverser.traverse(StubRoot(schema), visitor)
        } catch (err: Exception) {
            Assertions.fail(err.message)
        }
    }
}
