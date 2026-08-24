package viaduct.engine.runtime

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Caller
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
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
    fun `node resolver is the caller for detached work it starts`() =
        runTest {
            var capturedCaller: Caller? = null
            val objectType = schema.schema.getObjectType("Test")
            val delegate = MockNodeUnbatchedResolverExecutor(
                typeName = "Test",
                unbatchedResolveFn = { id, _, context ->
                    capturedCaller = (
                        context.createRootFieldReference(
                            listOf("test"),
                            objectType,
                            emptyMap(),
                        ) as ObjectRootFieldReference
                    ).caller
                    createEngineObjectData(objectType, mapOf("id" to id))
                },
            )
            val resolver = object : NodeResolverExecutor by delegate {
                override val metadata =
                    ResolverMetadata.forModern(
                        name = "TestNodeResolver",
                        resolverType = ResolverType.NODE,
                        tenantMetadata = TenantModuleMetadata("node-tenant"),
                    )
            }
            val dispatcher = NodeResolverDispatcherImpl(resolver)
            val dispatcherRegistry = DispatcherRegistry.Impl(
                emptyMap(),
                mapOf("Test" to dispatcher),
                emptyMap(),
                emptyMap(),
            )
            val context = ContextMocks(myFullSchema = schema).engineExecutionContextImpl

            NodeEngineObjectDataImpl(id1, objectType, dispatcherRegistry)
                .resolveData(
                    selectionSetFactory.engineSelectionSet("Test", "id", emptyMap()),
                    context,
                )

            assertEquals(
                Caller("node-tenant", "Test", null),
                capturedCaller,
            )
        }

    @Test
    fun `node cache hit does not invoke the resolver with another caller's provenance`() =
        runTest {
            val capturedCallers = mutableListOf<Caller?>()
            val objectType = schema.schema.getObjectType("Test")
            val delegate = MockNodeUnbatchedResolverExecutor(
                typeName = "Test",
                unbatchedResolveFn = { id, _, context ->
                    capturedCallers += (
                        context.createRootFieldReference(
                            listOf("test"),
                            objectType,
                            emptyMap(),
                        ) as ObjectRootFieldReference
                    ).caller
                    createEngineObjectData(objectType, mapOf("id" to id))
                },
            )
            val resolver = object : NodeResolverExecutor by delegate {
                override val metadata =
                    ResolverMetadata.forModern(
                        name = "TestNodeResolver",
                        resolverType = ResolverType.NODE,
                        tenantMetadata = TenantModuleMetadata("node-tenant"),
                    )
            }
            val baseContext = ContextMocks(myFullSchema = schema).engineExecutionContextImpl
            val selections = selectionSetFactory.engineSelectionSet("Test", "id", emptyMap())
            val dispatcher = NodeResolverDispatcherImpl(resolver)
            val dispatcherRegistry = DispatcherRegistry.Impl(
                emptyMap(),
                mapOf("Test" to dispatcher),
                emptyMap(),
                emptyMap(),
            )

            suspend fun resolveFrom(fieldName: String) {
                NodeEngineObjectDataImpl(
                    id1,
                    objectType,
                    dispatcherRegistry,
                    Caller(null, "Whatever", fieldName),
                ).resolveData(selections, baseContext)
            }

            resolveFrom("first")
            resolveFrom("first")
            resolveFrom("second")

            assertEquals(
                listOf(
                    Caller("node-tenant", "Test", null),
                ),
                capturedCallers,
            )
        }

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

    @OptIn(ExperimentalCoroutinesApi::class)
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
        }.runFeatureTest(withoutDefaultQueryNodeResolvers = true) {
            runQuery("""{ node(id: "1") { ... on Preamble { text } } }""")
                .assertJson("""{"data": {"node": {"text": "preamble text"}}}""")

            assertEquals(Caller(null, "Preamble", null), capturedCaller)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
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
        }.runFeatureTest(withoutDefaultQueryNodeResolvers = true) {
            runQuery("""{ node(id: "1") { ... on Preamble { content { text } } } }""")
                .assertJson("""{"data": {"node": {"content": {"text": "text for cfg"}}}}""")

            assertEquals(Caller(null, "Preamble", null), capturedCaller)
        }
    }

    private fun selector(selections: String) =
        NodeResolverExecutor.Selector(
            id = id1,
            selections = selectionSetFactory.engineSelectionSet("Test", selections, emptyMap()),
        )
}
