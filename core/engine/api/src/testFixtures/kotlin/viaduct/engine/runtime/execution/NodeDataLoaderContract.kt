@file:OptIn(viaduct.apiannotations.InternalApi::class)

package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Caller
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.graphql.test.assertJson

interface NodeDataLoaderContract : QueryExecutionContract {
    @Test
    fun `batch node resolution clears field scope to prevent cross-field contamination`() {
        // Track field scope fragments and variables in the batch node resolver
        var fragmentsInBatchResolver: Map<String, *>? = null
        var variablesInBatchResolver: Map<String, *>? = null

        val nodeSchema = """
            extend type Query {
                userList: [User!]!
            }
            type User implements Node {
                id: ID!
                name: String
            }
        """.trimIndent()

        EngineTestModule(nodeSchema) {
            field("Query" to "userList") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Return three user node references to ensure batching
                        (1..3).map { i ->
                            ctx.createNodeReference(i.toString(), schema.schema.getObjectType("User"))
                        }
                    }
                }
            }
            type("User") {
                nodeBatchedExecutor { selectors, context ->
                    // Capture the field scope from the context during batch resolution
                    // Only capture on first call to avoid overwriting
                    if (fragmentsInBatchResolver == null) {
                        fragmentsInBatchResolver = context.fieldScope.fragments
                        variablesInBatchResolver = context.fieldScope.variables
                    }
                    selectors.associateWith { selector ->
                        Result.success(
                            createEngineObjectData(
                                objectType,
                                mapOf(
                                    "id" to selector.id,
                                    // Include batch size to prove batching happened
                                    "name" to "User-${selector.id}-batch:${selectors.size}"
                                )
                            )
                        )
                    }
                }
            }
        }.runContractTest {
            // Run a query - field scope clearing happens in internalLoad during batch resolution
            val result = runQuery(
                """
                {
                    userList {
                        id
                        name
                    }
                }
                """.trimIndent()
            )

            // Verify batching happened (all users should show batch:3)
            result.assertJson(
                """
                {
                  "data": {
                    "userList": [
                      {"id": "1", "name": "User-1-batch:3"},
                      {"id": "2", "name": "User-2-batch:3"},
                      {"id": "3", "name": "User-3-batch:3"}
                    ]
                  }
                }
                """.trimIndent()
            )

            // Assert: The batch resolver should have received a context with CLEARED field scope
            assertTrue(fragmentsInBatchResolver != null, "Fragments map should have been captured")
            assertTrue(variablesInBatchResolver != null, "Variables map should have been captured")
            assertTrue(
                fragmentsInBatchResolver!!.isEmpty(),
                "Field scope fragments should be cleared for batch resolution to prevent contamination"
            )
            assertTrue(
                variablesInBatchResolver!!.isEmpty(),
                "Field scope variables should be cleared for batch resolution to prevent contamination"
            )
        }
    }

    @Test
    fun `node resolver is the caller for fields selected under the node`() {
        var capturedCaller: Caller? = null

        val nodeSchema = """
            type Preamble implements Node {
                id: ID!
                text: String
            }
        """.trimIndent()

        EngineTestModule(nodeSchema) {
            field("Query" to "node") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("1", schema.schema.getObjectType("Preamble"))
                    }
                }
            }
            type("Preamble") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(objectType, mapOf("id" to id))
                }
            }
            field("Preamble" to "text") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        capturedCaller = ctx.fieldScope.caller
                        "preamble text"
                    }
                }
            }
        }.runContractTest(withoutDefaultQueryNodeResolvers = true) {
            runQuery("""{ node(id: "1") { ... on Preamble { text } } }""")
                .assertJson("""{"data": {"node": {"text": "preamble text"}}}""")

            assertEquals(Caller(null, "Preamble", null), capturedCaller)
        }
    }

    @Test
    fun `node resolver is the caller for a nested RSS-backed resolver under the node`() {
        var capturedCaller: Caller? = null

        val nodeSchema = """
            type Preamble implements Node {
                id: ID!
                content: Content
            }
            type Content {
                config: String
                text: String
            }
        """.trimIndent()

        EngineTestModule(nodeSchema) {
            field("Query" to "node") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("1", schema.schema.getObjectType("Preamble"))
                    }
                }
            }
            type("Preamble") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "id" to id,
                            "content" to createEngineObjectData(
                                schema.schema.getObjectType("Content"),
                                mapOf("config" to "cfg"),
                            ),
                        )
                    )
                }
            }
            field("Content" to "text") {
                resolver {
                    objectSelections("config")
                    fn { _, objectValue, _, _, ctx ->
                        capturedCaller = ctx.fieldScope.caller
                        "text for ${(objectValue as EngineObjectData).fetch("config")}"
                    }
                }
            }
        }.runContractTest(withoutDefaultQueryNodeResolvers = true) {
            runQuery("""{ node(id: "1") { ... on Preamble { content { text } } } }""")
                .assertJson("""{"data": {"node": {"content": {"text": "text for cfg"}}}}""")

            assertEquals(Caller(null, "Preamble", null), capturedCaller)
        }
    }
}
