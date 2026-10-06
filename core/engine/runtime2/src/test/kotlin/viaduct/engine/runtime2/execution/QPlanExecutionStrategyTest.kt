@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.execution

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.execution.testing.ExecutionTestFixture
import viaduct.engine.runtime2.execution.testing.ExecutionTestFixtureResource
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld

class QPlanExecutionStrategyTest : ExecutionTestFixtureResource {
    @Test
    fun `nonnull response failure waits for every mutation to finish`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val result = suspendedMutationFixture(started, release, finished).runQueryAsync("mutation { failed later }")
            try {
                withTimeout(5_000) { started.await() }
                assertFalse(result.isDone)
                release.complete(Unit)
                val response = result.get(5, TimeUnit.SECONDS)
                assertTrue(finished.isCompleted)
                assertNull(response.getData<Any?>())
                assertEquals(listOf("failed"), response.errors.single().path)
            } finally {
                release.complete(Unit)
                result.cancel(true)
            }
        }

    private fun suspendedMutationFixture(
        started: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
        finished: CompletableDeferred<Unit>,
    ): ExecutionTestFixture {
        val sdl = "type Query { idle: Int } type Mutation { failed: Int! later: Int! }"
        val world = TestWorld.fromSDL(
            sdl,
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Mutation", "failed") to fieldResolverOf(schema.emptyFragmentOf("Mutation")) { _, _ ->
                        EngineErrorData.of(IllegalStateException("mutation failed"))
                    },
                    schema.requireObjectField("Mutation", "later") to fieldResolverOf(schema.emptyFragmentOf("Mutation")) { _, _ ->
                        started.complete(Unit)
                        try {
                            release.await()
                            2
                        } finally {
                            finished.complete(Unit)
                        }
                    },
                )
            },
        )
        return fixtureFromWorld(sdl, world)
    }

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
