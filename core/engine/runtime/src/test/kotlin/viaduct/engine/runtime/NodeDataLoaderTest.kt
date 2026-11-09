package viaduct.engine.runtime

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.Caller
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.engine.runtime.select.ProjectedEngineSelectionSet
import viaduct.service.api.spi.FlagManager

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

    @Suppress("DEPRECATION")
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `cache policy survives context copies and shadow forks`(cachingEnabled: Boolean) =
        runTest {
            var calls = 0
            val objectType = schema.schema.getObjectType("Test")
            val resolver = MockNodeUnbatchedResolverExecutor(typeName = "Test") { id, _, _ ->
                calls++
                createEngineObjectData(objectType, mapOf("id" to id))
            }
            val mocks = ContextMocks(myFullSchema = schema, myFlagManager = FlagManager.Disabled)
            val factory = EngineExecutionContextFactory(
                fullSchema = mocks.fullSchema,
                dispatcherRegistry = mocks.dispatcherRegistry,
                resolverInstrumentation = mocks.resolverInstrumentation,
                flagManager = mocks.flagManager,
                engine = mocks.engine,
                globalIDCodec = mocks.globalIDCodec,
                meterRegistry = null,
                materializedFieldValueReader = viaduct.engine.api.spi.MaterializedFieldValueReader.Default,
                nodeResolverCachingEnabled = cachingEnabled,
            )
            val context = factory.create(schema, null) as EngineExecutionContextImpl
            val selector = NodeResolverExecutor.Selector(id1, selectionSetFactory.engineSelectionSet("Test", "id", emptyMap()))
            val copied = context.copy()
            context.nodeDataLoader(resolver).loadByKey(selector, context).getOrThrow()
            copied.nodeDataLoader(resolver).loadByKey(selector, copied).getOrThrow()
            assertEquals(if (cachingEnabled) 1 else 2, calls)
            val shadow = copied.forkForShadowExecution()
            repeat(2) { shadow.nodeDataLoader(resolver).loadByKey(selector, shadow).getOrThrow() }
            assertEquals(if (cachingEnabled) 2 else 4, calls)
        }

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
    fun `loadByKey reuses cached result`() =
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

    @Test
    fun `loadByKey caches covering selections independently for each ID`() =
        runTest {
            val resolvedIds = mutableListOf<String>()
            val resolver = MockNodeUnbatchedResolverExecutor(
                typeName = "Test",
                isSelective = true,
            ) { id, _, _ ->
                resolvedIds += id
                createEngineObjectData(schema.schema.getObjectType("Test"), mapOf("id" to id))
            }
            val loader = NodeDataLoader(resolver)
            val context = mockk<EngineExecutionContext>()

            val first = loader.loadByKey(selector("foo { a b }", id1), context).getOrThrow()
            val second = loader.loadByKey(selector("foo { a b }", id2), context).getOrThrow()

            assertSame(first, loader.loadByKey(selector("foo { a }", id1), context).getOrThrow())
            assertSame(second, loader.loadByKey(selector("foo { a }", id2), context).getOrThrow())
            assertEquals(listOf(id1, id2), resolvedIds)
        }

    private fun selector(
        selections: String,
        id: String = id1
    ) = NodeResolverExecutor.Selector(
        id = id,
        selections = selectionSetFactory.engineSelectionSet("Test", selections, emptyMap()),
    )
}
