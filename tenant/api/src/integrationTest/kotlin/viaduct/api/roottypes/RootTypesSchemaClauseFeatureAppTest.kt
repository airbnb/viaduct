@file:Suppress("unused", "ClassName")

package viaduct.api.roottypes

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.roottypes.resolverbases.CustomMutationResolvers
import viaduct.api.roottypes.resolverbases.CustomQueryResolvers
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Integration test demonstrating that SDL with a schema clause that renames the Query and Mutation
 * root types works end-to-end.
 *
 * This tests the root types plugin functionality that allows schemas to declare custom root type names
 * via the GraphQL schema clause:
 * ```graphql
 * schema {
 *   query: CustomQuery
 *   mutation: CustomMutation
 * }
 * ```
 *
 * Note: This test intentionally does not use Node/implements Node to avoid triggering the default
 * schema provider's addition of `extend type Query` for node/nodes fields, which would fail since
 * there's no base Query type (we're using CustomQuery instead).
 */
class RootTypesSchemaClauseFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        |
        | schema {
        |   query: CustomQuery
        |   mutation: CustomMutation
        | }
        |
        | type CustomQuery {
        |   greeting(name: String!): String @resolver
        |   echo(message: String!): String @resolver
        | }
        |
        | type CustomMutation {
        |   saveMessage(content: String!): SaveMessagePayload @resolver
        | }
        |
        | type SaveMessagePayload {
        |   messageId: String
        |   content: String
        | }
        |
        | #END_SCHEMA
    """.trimMargin()

    @Resolver
    class CustomQuery_GreetingResolver : CustomQueryResolvers.Greeting() {
        override suspend fun resolve(ctx: Context): String {
            return "Hello, ${ctx.arguments.name}!"
        }
    }

    @Resolver
    class CustomQuery_EchoResolver : CustomQueryResolvers.Echo() {
        override suspend fun resolve(ctx: Context): String {
            return ctx.arguments.message
        }
    }

    @Resolver
    class CustomMutation_SaveMessageResolver : CustomMutationResolvers.SaveMessage() {
        override suspend fun resolve(ctx: Context): SaveMessagePayload {
            val messageId = "msg-${ctx.arguments.content.hashCode()}"
            return SaveMessagePayload.Builder(ctx)
                .messageId(messageId)
                .content(ctx.arguments.content)
                .build()
        }
    }

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
        // First, save a message via mutation
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

        // Then, run a query to verify the system still works
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
}
