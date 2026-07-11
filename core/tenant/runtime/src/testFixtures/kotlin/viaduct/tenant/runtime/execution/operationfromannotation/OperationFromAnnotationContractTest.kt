package viaduct.tenant.runtime.execution.operationfromannotation

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for executing @GraphQLOperation strings via ctx.query / ctx.mutation. The mutation
 * case runs from a Mutation-rooted resolver, since ctx.mutation is only available there.
 */
@TestSchema(
    """
    extend type Query {
      container: Container @resolver
      "Return \"echo:<value>\""
      echo(value: String!): String! @resolver
      "Return a Greeter with text=\"hi\""
      greeter: Greeter @resolver
    }

    extend type Mutation {
      "Return \"record:<value>\""
      record(value: String!): String! @resolver
      "Run a @GraphQLOperation mutation that calls record with a variable; return its result."
      runMutationOperation(value: String!): String! @resolver
    }

    type Container {
      "Run a @GraphQLOperation query that calls echo with a variable; return its result."
      runQueryOperation(value: String!): String! @resolver
      "Run a @GraphQLOperation query that spreads an external @GraphQLFragment; return greeter.text."
      runQueryWithFragment: String! @resolver
    }

    type Greeter {
      text: String!
    }
"""
)
abstract class OperationFromAnnotationContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `ctx query executes a GraphQLOperation query`() {
        execute(
            query = """
                query {
                    container {
                        runQueryOperation(value: "hello")
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "container" to {
                    "runQueryOperation" to "echo:hello"
                }
            }
        }
    }

    @Test
    fun `ctx query executes a GraphQLOperation query that spreads an external GraphQLFragment`() {
        execute(
            query = """
                query {
                    container {
                        runQueryWithFragment
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "container" to {
                    "runQueryWithFragment" to "hi"
                }
            }
        }
    }

    @Test
    fun `ctx mutation executes a GraphQLOperation mutation`() {
        execute(
            query = """
                mutation {
                    runMutationOperation(value: "world")
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "runMutationOperation" to "record:world"
            }
        }
    }
}
