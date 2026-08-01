package viaduct.engine.runtime

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.engine.runtime.select.ProjectedEngineSelectionSet

class NodeDataLoaderTest {
    private val id1 = "1"
    private val id2 = "2"
    val schema = createSchema(
        """
        type Query { test: Test }
        interface Node { id: ID! }
        type Test implements Node { id: ID! foo: Foo bar: String}
        type Foo { id: ID! a: String b: String }
        """.trimIndent()
    )
    private val selectionSetFactory = EngineSelectionSetFactoryImpl(schema)

    @Test
    fun `covers returns true for exact match`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "id bar", emptyMap())
        )
        assertTrue(selector.covers(selector))
    }

    @Test
    fun `covers returns true for larger selection set`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "id bar foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { a } bar", emptyMap())
        )

        assertTrue(selector.covers(other))
    }

    @Test
    fun `covers returns false for different nested selections`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { b }", emptyMap())
        )

        assertFalse(selector.covers(other))
    }

    @Test
    fun `covers returns true for top-level ID`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "id", emptyMap())
        )

        assertTrue(selector.covers(other))
    }

    @Test
    fun `covers returns true for aliased top-level ID`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "nodeId: id", emptyMap())
        )

        assertTrue(selector.covers(other))
    }

    @Test
    fun `covers returns true for top-level __typename`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "__typename", emptyMap())
        )

        assertTrue(selector.covers(other))
    }

    @Test
    fun `covers returns true for aliased top-level __typename`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "nodeType: __typename", emptyMap())
        )

        assertTrue(selector.covers(other))
    }

    @Test
    fun `covers returns true for nested __typename`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { __typename }", emptyMap())
        )

        assertTrue(selector.covers(other))
    }

    @Test
    fun `covers returns true for aliased nested __typename`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { fooType: __typename }", emptyMap())
        )

        assertTrue(selector.covers(other))
    }

    @Test
    fun `covers returns false for nested ID`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { a }", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "foo { id }", emptyMap())
        )

        assertFalse(selector.covers(other))
    }

    @Test
    fun `covers returns false for different ID`() {
        val selector = NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", "bar", emptyMap())
        )
        val other = NodeResolverExecutor.Selector(
            id = id2,
            selections = selectionSetFactory.engineSelectionSet("Test", "bar", emptyMap())
        )

        assertFalse(selector.covers(other))
    }

    @Test
    fun `selectors compare equal when one side holds a projected selection set`() {
        val projectedSelections = selectionSetFactory
            .engineSelectionSet("Node", "id ... on Test { bar }", emptyMap())
            .selectionSetForType("Test")
        assertTrue(projectedSelections is ProjectedEngineSelectionSet)

        val sourceSelections = (projectedSelections as ProjectedEngineSelectionSet).sourceImpl
        val selector = NodeResolverExecutor.Selector("id1", sourceSelections)
        val other = NodeResolverExecutor.Selector("id1", projectedSelections)

        assertEquals(selector, other)
        assertEquals(selector.hashCode(), other.hashCode())
    }

    @Test
    fun `loadByKey reuses cached result when cached selections cover requested selections`() =
        runTest {
            var resolveCount = 0
            val resolver = MockNodeUnbatchedResolverExecutor(
                typeName = "Test",
                isSelective = true,
            ) { _, _, _ ->
                resolveCount++
                createEngineObjectData(schema.schema.getObjectType("Test"), emptyMap())
            }
            val loader = NodeDataLoader(resolver)
            val context = mockk<EngineExecutionContext>()

            loader.loadByKey(selector("foo { a b }"), context)
            // load a subset: previous load should cover this one
            loader.loadByKey(selector("foo { a }"), context)

            assertEquals(1, resolveCount)
        }

    @Test
    fun `loadByKey resolves again when cached selections do not cover requested selections`() =
        runTest {
            var resolveCount = 0
            val resolver = MockNodeUnbatchedResolverExecutor(
                typeName = "Test",
                isSelective = true,
            ) { _, _, _ ->
                resolveCount++
                createEngineObjectData(schema.schema.getObjectType("Test"), emptyMap())
            }
            val loader = NodeDataLoader(resolver)
            val context = mockk<EngineExecutionContext>()

            loader.loadByKey(selector("foo { a }"), context).getOrThrow()
            // load a superset: previous load does not cover this one
            loader.loadByKey(selector("foo { a b }"), context).getOrThrow()

            assertEquals(2, resolveCount)
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

    private fun selector(selections: String) =
        NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", selections, emptyMap()),
        )
}
