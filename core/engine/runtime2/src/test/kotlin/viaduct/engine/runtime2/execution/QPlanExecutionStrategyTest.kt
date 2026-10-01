package viaduct.engine.runtime2.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.execution.testing.ExecutionTestFixtureResource

class QPlanExecutionStrategyTest : ExecutionTestFixtureResource {
    @Test
    fun `resolves an operation with variables objects and lists`() {
        val fixture =
            fixtureFromResolverDSL(
                schemaSDL = VALUE_SCHEMA,
                resolverSchemaSDL = VALUE_RESOLVERS,
            )

        val result =
            fixture.runQuery(
                """
                query Resolve(${'$'}seed: Int!, ${'$'}extra: Int!) {
                  incremented: total(seed: ${'$'}seed)
                  box: container {
                    values {
                      value
                    }
                    total(extra: ${'$'}extra)
                  }
                }
                """.trimIndent(),
                variables =
                    mapOf(
                        "seed" to 5,
                        "extra" to 4,
                    ),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "incremented" to 6,
                "box" to
                    mapOf(
                        "values" to
                            listOf(
                                mapOf("value" to 2),
                                null,
                                mapOf("value" to 3),
                            ),
                        "total" to 10,
                    ),
            ),
            result.getData(),
        )
    }

    @Test
    fun `resolves a Node through the lowered bridge`() {
        val fixture =
            fixtureFromResolverDSL(
                schemaSDL = NODE_SCHEMA,
                resolverSchemaSDL = NODE_RESOLVERS,
            )

        val result =
            fixture.runQuery(
                """
                query Viewer(${'$'}id: ID!) {
                  account: viewer(id: ${'$'}id) {
                    id
                    score
                  }
                }
                """.trimIndent(),
                variables = mapOf("id" to "user-2"),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "account" to
                    mapOf(
                        "id" to "user-2",
                        "score" to 8,
                    ),
            ),
            result.getData(),
        )
    }

    private companion object {
        val VALUE_SCHEMA =
            """
            type Query {
              total(seed: Int!): Int!
              container: Container!
            }

            type Container {
              values: [Item]!
              total(extra: Int!): Int!
            }

            type Item {
              value: Int!
            }
            """.trimIndent()

        val VALUE_RESOLVERS =
            """
            extend type Query {
              total(seed: Int!): Int!
                @resolver(result: "sumplus1(${'$'}seed)")
              container: Container!
                @resolver(result: {values: [{value: 2}, null, {value: 3}]})
            }

            type Container {
              values: [Item]!
              total(extra: Int!): Int!
                @resolver(
                  of: "values { value }"
                  result: "sumplus1(values.value, ${'$'}extra)"
                )
            }

            type Item {
              value: Int!
            }
            """.trimIndent()

        val NODE_SCHEMA =
            """
            interface Node {
              id: ID!
            }

            type Query {
              viewer(id: ID!): User!
            }

            type User implements Node {
              id: ID!
              score: Int!
            }
            """.trimIndent()

        val NODE_RESOLVERS =
            """
            extend type Query {
              viewer(id: ID!): User!
                @resolver(result: {id: "idFrom(${'$'}id)"})
            }

            type User implements Node
              @nodeResolver(
                result: [
                  {id: "user-1", result: {score: 7}},
                  {id: "user-2", result: {score: 8}}
                ]
              ) {
              id: ID!
              score: Int!
            }
            """.trimIndent()
    }
}
