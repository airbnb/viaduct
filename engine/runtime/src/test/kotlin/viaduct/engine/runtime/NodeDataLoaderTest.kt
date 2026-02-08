package viaduct.engine.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl
import viaduct.graphql.test.assertJson

class NodeDataLoaderTest {
    private val id1 = "1"
    private val id2 = "2"
    val schema = mkSchema(
        """
        type Query { test: Test }
        interface Node { id: ID! }
        type Test implements Node { id: ID! foo: Foo bar: String}
        type Foo { a: String }
        """.trimIndent()
    )
    private val selectionSetFactory = RawSelectionSetFactoryImpl(schema)

    @Test
    fun `covers returns true for exact match`() {
        val selector = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id bar", emptyMap())
        )
        assertTrue(selector.covers(selector, isSelective = false))
    }

    @Test
    fun `covers returns true for larger selection set`() {
        val selector = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id bar foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "foo { a } bar", emptyMap())
        )
        assertTrue(selector.covers(other, isSelective = false))
    }

    @Test
    fun `covers returns false for different ID`() {
        val selections = selectionSetFactory.rawSelectionSet("Test", "id bar", emptyMap())
        val selector = NodeResolverExecutor.Selector("id1", selections)
        val other = NodeResolverExecutor.Selector("id2", selections)
        assertFalse(selector.covers(other, isSelective = false))
    }

    @Test
    fun `covers returns true for smaller selection set when non-selective`() {
        // Non-selective resolvers always return their full output, so ID match is sufficient
        val selector = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id foo { a } bar", emptyMap())
        )
        assertTrue(selector.covers(other, isSelective = false))
    }

    @Test
    fun `covers returns false for smaller selection set when selective`() {
        // Selective resolvers tailor their response to requested fields,
        // so cached entry must cover all requested fields
        val selector = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id foo { a } bar", emptyMap())
        )
        assertFalse(selector.covers(other, isSelective = true))
    }

    @Test
    fun `covers returns true for larger selection set when selective`() {
        // When cached entry has more fields than requested, it's a valid cache hit
        val selector = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id bar foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "foo { a } bar", emptyMap())
        )
        assertTrue(selector.covers(other, isSelective = true))
    }

    @Test
    fun `covers returns true for exact match when selective`() {
        val selector = NodeResolverExecutor.Selector(
            id = "id1",
            selections = selectionSetFactory.rawSelectionSet("Test", "id bar", emptyMap())
        )
        assertTrue(selector.covers(selector, isSelective = true))
    }

    @Test
    fun `covers returns false for different ID when selective`() {
        val selections = selectionSetFactory.rawSelectionSet("Test", "id bar", emptyMap())
        val selector = NodeResolverExecutor.Selector("id1", selections)
        val other = NodeResolverExecutor.Selector("id2", selections)
        assertFalse(selector.covers(other, isSelective = true))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
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

        MockTenantModuleBootstrapper(nodeSchema) {
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
                            mkEngineObjectData(
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
        }.runFeatureTest {
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
}
