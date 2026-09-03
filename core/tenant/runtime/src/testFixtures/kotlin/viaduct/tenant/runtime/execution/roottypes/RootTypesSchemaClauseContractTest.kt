package viaduct.tenant.runtime.execution.roottypes

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for schemas with custom root type names via the schema clause.
 *
 * Verifies that schema-declared root type names work end-to-end for Kotlin and Java tenants.
 *
 * Note: This test intentionally does not use Node/implements Node to avoid triggering the default
 * schema provider's addition of `extend type Query` for node/nodes fields, which would fail since
 * there's no base Query type (we're using CustomQuery instead).
 */
@TestSchema(
    """
    schema {
      query: CustomQuery
      mutation: CustomMutation
    }

    type CustomQuery {
      greeting(name: String!): String @resolver
      echo(message: String!): String @resolver
      selectedGreeting: String @resolver
      queriedGreeting(name: String!): String @resolver
    }

    type CustomMutation {
      saveMessage(content: String!): SaveMessagePayload @resolver
      relayMessage(content: String!): SaveMessagePayload @resolver
    }

    type SaveMessagePayload {
      messageId: String
      content: String
    }
"""
)
abstract class RootTypesSchemaClauseContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `query with custom root type name works end-to-end`() {
        val result = execute(
            query = """
                query {
                    greeting(name: "World")
                }
            """.trimIndent()
        )

        result.assertEquals {
            "data" to {
                "greeting" to "Hello, World!"
            }
        }
    }

    @Test
    fun `multiple query fields with custom root type name work`() {
        val result = execute(
            query = """
                query {
                    greeting(name: "Alice")
                    echo(message: "test message")
                }
            """.trimIndent()
        )

        result.assertEquals {
            "data" to {
                "greeting" to "Hello, Alice!"
                "echo" to "test message"
            }
        }
    }

    @Test
    fun `required selections and subqueries use the custom query root`() {
        val result = execute(
            query = """
                query {
                    selectedGreeting
                    queriedGreeting(name: "Runtime")
                }
            """.trimIndent()
        )

        result.assertEquals {
            "data" to {
                "selectedGreeting" to "Hello, Selection!"
                "queriedGreeting" to "Hello, Runtime!"
            }
        }
    }

    @Test
    fun `mutation with custom root type name works end-to-end`() {
        val result = execute(
            query = """
                mutation {
                    saveMessage(content: "Hello from mutation") {
                        messageId
                        content
                    }
                }
            """.trimIndent()
        )

        result.assertEquals {
            "data" to {
                "saveMessage" to {
                    "messageId" to "msg-${("Hello from mutation").hashCode()}"
                    "content" to "Hello from mutation"
                }
            }
        }
    }

    @Test
    fun `query and mutation work together with custom root type names`() {
        val mutationResult = execute(
            query = """
                mutation {
                    saveMessage(content: "Persisted message") {
                        messageId
                        content
                    }
                }
            """.trimIndent()
        )

        mutationResult.assertEquals {
            "data" to {
                "saveMessage" to {
                    "messageId" to "msg-${("Persisted message").hashCode()}"
                    "content" to "Persisted message"
                }
            }
        }

        val queryResult = execute(
            query = """
                query {
                    greeting(name: "Mutation User")
                    echo(message: "After mutation")
                }
            """.trimIndent()
        )

        queryResult.assertEquals {
            "data" to {
                "greeting" to "Hello, Mutation User!"
                "echo" to "After mutation"
            }
        }
    }

    @Test
    fun `submutations use the custom mutation root`() {
        val result = execute(
            query = """
                mutation {
                    relayMessage(content: "Relayed message") {
                        messageId
                        content
                    }
                }
            """.trimIndent()
        )

        result.assertEquals {
            "data" to {
                "relayMessage" to {
                    "messageId" to "msg-${("Relayed message").hashCode()}"
                    "content" to "Relayed message"
                }
            }
        }
    }
}
