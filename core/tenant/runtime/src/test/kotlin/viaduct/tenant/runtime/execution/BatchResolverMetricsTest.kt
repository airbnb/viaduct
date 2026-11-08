@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import viaduct.api.FieldValue
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.BaseBatchedFieldResolver
import viaduct.api.internal.BaseBatchedNodeResolver
import viaduct.api.internal.BaseUnbatchedFieldResolver
import viaduct.api.internal.BaseUnbatchedNodeResolver
import viaduct.api.internal.ObjectBase
import viaduct.engine.EngineConfiguration
import viaduct.engine.EngineFactory
import viaduct.engine.api.Engine
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.FullSchema
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.NodeResolverDispatcherImpl
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedNodeResolverDispatcher
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.withInvocationContexts
import viaduct.errors.TenantResolverException
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory

class BatchResolverMetricsTest {
    private val batches = mutableListOf<Pair<ResolverMetadata, Int>>()
    private val dispatches = mutableListOf<Pair<ResolverMetadata, Int>>()
    private val executionSchema = createSchemaWithWiring("extend type Query { value: String, user: TestNode } type TestNode implements Node { id: ID!, name: String }")
    private val schema = MockSchema.mk("type TestNode { id: ID }")
    private val context = ContextMocks(myFullSchema = schema).engineExecutionContext
    private val state = object : ViaductResolverInstrumentation.InstrumentationState {}
    private val instrumentationContext = ResolverInstrumentationContext(
        object : ViaductResolverInstrumentation {
            override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters) = state

            override fun onBatchResolverDispatch(
                metadata: ResolverMetadata,
                batchSize: Int,
                state: ViaductResolverInstrumentation.InstrumentationState?,
            ) {
                assertSame(this@BatchResolverMetricsTest.state, state)
                dispatches.add(metadata to batchSize)
            }

            override fun onBatchResolverInvocation(
                metadata: ResolverMetadata,
                batchSize: Int,
                state: ViaductResolverInstrumentation.InstrumentationState?,
            ) {
                assertSame(this@BatchResolverMetricsTest.state, state)
                batches.add(metadata to batchSize)
            }
        },
        state,
    )

    private val fieldFactory = mockk<FieldExecutionContextFactory> {
        every { this@mockk.invoke(any(), any(), any(), any(), any(), any()) } answers { mockk() }
    }
    private val nodeFactory = mockk<NodeExecutionContextFactory> {
        every { this@mockk.invoke(any(), any(), any(), any()) } answers { mockk() }
    }

    @Test
    fun `successful field batches record once at invocation entry including singletons`(): Unit =
        runTest {
            val resolver = object : BaseBatchedFieldResolver {
                override suspend fun invokeFieldBatchResolver(contexts: List<BaseFieldExecutionContext<*, *, *>>): Any? {
                    assertEquals(contexts.size, batchSizes("Query.batch", "field").last())
                    return contexts.map { FieldValue.ofValue("value") }
                }
            }
            val executor = fieldExecutor(resolver)

            executor.batchResolve(List(3) { fieldSelector() }, context, instrumentationContext)
            executor.batchResolve(listOf(fieldSelector()), context, instrumentationContext)

            assertEquals(listOf(3, 1), dispatchSizes("Query.batch", "field"))
            assertEquals(listOf(3, 1), batchSizes("Query.batch", "field"))
        }

    @Test
    fun `failed field batches record once before tenant code throws`(): Unit =
        runTest {
            val resolver = object : BaseBatchedFieldResolver {
                override suspend fun invokeFieldBatchResolver(contexts: List<BaseFieldExecutionContext<*, *, *>>): Any? {
                    assertEquals(listOf(2), batchSizes("Query.batch", "field"))
                    error("tenant failure")
                }
            }
            val executor = fieldExecutor(resolver)

            assertThrows<TenantResolverException> { executor.batchResolve(List(2) { fieldSelector() }, context, instrumentationContext) }

            assertEquals(listOf(2), dispatchSizes("Query.batch", "field"))
            assertEquals(listOf(2), batchSizes("Query.batch", "field"))
        }

    @Test
    fun `node dispatch records each partition of unique IDs including failed singleton`(): Unit =
        runTest {
            val resolver = object : BaseBatchedNodeResolver {
                override suspend fun invokeNodeBatchResolver(contexts: List<NodeExecutionContext<*>>): Map<NodeExecutionContext<*>, FieldValue<*>> {
                    assertEquals(contexts.size, batchSizes("TestNode", "node").last())
                    if (contexts.size == 1) error("singleton failure")
                    return contexts.associateWith { FieldValue.ofError(IllegalStateException("item failure")) }
                }
            }
            val executor = NodeBatchResolverExecutorImpl(Provider { resolver }, "TestNode", nodeFactory, "TestNode", false)
            val selectors = listOf(nodeSelector("a"), nodeSelector("b"), nodeSelector("a", "id __typename"))
            val batchContext = context.withInvocationContexts(selectors.associateWith { context })
            val result = executor.resolve(selectors, batchContext, instrumentationContext)

            assertEquals(3, result.size)
            assertTrue(result.values.all { it.isFailure })
            assertEquals(listOf(3), dispatchSizes("TestNode", "node"))
            assertEquals(listOf(2, 1), batchSizes("TestNode", "node"))
        }

    @Test
    fun `context construction failure counts as a dispatch but not a resolver invocation`(): Unit =
        runTest {
            every { fieldFactory.invoke(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("context failure")
            val resolver = mockk<BaseBatchedFieldResolver>()

            assertThrows<IllegalStateException> {
                fieldExecutor(resolver).batchResolve(listOf(fieldSelector()), context, instrumentationContext)
            }

            assertEquals(listOf(1), dispatchSizes("Query.batch", "field"))
            assertTrue(batches.isEmpty())
        }

    @Test
    fun `node resolver construction failure counts as a dispatch without an invocation`(): Unit =
        runTest {
            val executor = NodeBatchResolverExecutorImpl(
                Provider { error("resolver construction failure") },
                "TestNode",
                nodeFactory,
                "TestNode",
                false
            )

            assertThrows<IllegalStateException> {
                executor.resolve(listOf(nodeSelector("a")), context, instrumentationContext)
            }

            assertEquals(listOf(1), dispatchSizes("TestNode", "node"))
            assertTrue(batches.isEmpty())
        }

    @Test
    fun `unbatched field invocations do not record batch sizes`(): Unit =
        runTest {
            val resolver = object : BaseUnbatchedFieldResolver {
                override suspend fun invokeFieldResolver(context: BaseFieldExecutionContext<*, *, *>): Any? = "value"
            }
            val executor = unbatchedFieldExecutor(resolver)

            val result = executor.batchResolve(listOf(fieldSelector()), context, instrumentationContext)

            assertTrue(result.values.single().isSuccess)
            assertTrue(dispatches.isEmpty())
            assertTrue(batches.isEmpty())
        }

    @Test
    fun `failed unbatched node invocations do not record batch sizes`(): Unit =
        runTest {
            val resolver = object : BaseUnbatchedNodeResolver {
                override suspend fun invokeNodeResolver(context: NodeExecutionContext<*>): Any? = error("unbatched failure")
            }
            val executor = unbatchedNodeExecutor(resolver)

            val result = executor.resolve(listOf(nodeSelector("a")), context, instrumentationContext)

            assertTrue(result.values.single().isFailure)
            assertTrue(dispatches.isEmpty())
            assertTrue(batches.isEmpty())
        }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `both engines report field and node batches on success and failure`(
        engine2: Boolean,
        fail: Boolean
    ): Unit =
        runTest {
            val fieldResolver = object : BaseBatchedFieldResolver {
                override suspend fun invokeFieldBatchResolver(contexts: List<BaseFieldExecutionContext<*, *, *>>): Any? {
                    check(!fail) { "field failure" }
                    return contexts.map { FieldValue.ofValue("value") }
                }
            }
            val nodeResolver = object : BaseBatchedNodeResolver {
                override suspend fun invokeNodeBatchResolver(contexts: List<NodeExecutionContext<*>>): Map<NodeExecutionContext<*>, FieldValue<*>> {
                    check(!fail) { "node failure" }
                    val data = createEngineObjectData(executionSchema.schema.getObjectType("TestNode"), mapOf("name" to "Ada"))
                    val user = object : ObjectBase(mockk(), data) {}
                    return contexts.associateWith { FieldValue.ofValue(user) }
                }
            }
            val engine = engine(engine2, fieldResolver, nodeResolver)

            val result = EngineConfiguration.default.coroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                engine.execute(ExecutionInput("{ value user { name } }", requestContext = null))
            }.await()

            assertEquals(if (fail) 2 else 0, result.errors.size, result.errors.toString())
            if (!fail) assertEquals(mapOf("value" to "value", "user" to mapOf("name" to "Ada")), result.getData())
            for ((name, kind) in listOf("Query.batch" to "field", "TestNode" to "node")) {
                assertEquals(listOf(1), dispatchSizes(name, kind))
                assertEquals(listOf(1), batchSizes(name, kind))
            }
            assertEquals(2, dispatches.size)
            assertEquals(2, batches.size)
        }

    private fun engine(
        engine2: Boolean,
        fieldResolver: BaseBatchedFieldResolver,
        nodeResolver: BaseBatchedNodeResolver,
    ): Engine {
        val instrumentation = instrumentationContext.instrumentation
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = mapOf(
                ("Query" to "nodes") to FieldResolverDispatcherImpl(MockFieldUnbatchedResolverExecutor.Null),
                ("Query" to "node") to FieldResolverDispatcherImpl(MockFieldUnbatchedResolverExecutor.Null),
                ("Query" to "value") to FieldResolverDispatcherImpl(fieldExecutor(fieldResolver)),
                ("Query" to "user") to FieldResolverDispatcherImpl(
                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.user") { _, _, _, _, context ->
                        context.createNodeReference(context.globalIDCodec.serialize("TestNode", "1"), executionSchema.schema.getObjectType("TestNode"))
                    },
                ),
            ),
            nodeResolverDispatchers = mapOf(
                "TestNode" to InstrumentedNodeResolverDispatcher(
                    NodeResolverDispatcherImpl(NodeBatchResolverExecutorImpl(Provider { nodeResolver }, "TestNode", nodeFactory, "TestNode", false)),
                    instrumentation
                )
            ),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        val flags = object : FlagManager {
            override fun isEnabled(flag: FlagManager.Flag): Boolean = engine2 && flag == FlagManager.Flags.ENGINE2_ENABLED
        }
        return EngineFactory(
            EngineConfiguration.default.copy(flagManager = flags, resolverInstrumentation = instrumentation),
            registry
        ).create(executionSchema, fullSchema = FullSchema(executionSchema))
    }

    private fun dispatchSizes(
        name: String,
        kind: String
    ) = dispatches.filter {
        it.first.name == name && it.first.flavor == "modern" && it.first.resolverType.name.lowercase() == kind
    }.map { it.second }

    private fun batchSizes(
        name: String,
        kind: String
    ) = batches.filter {
        it.first.name == name && it.first.flavor == "modern" && it.first.resolverType.name.lowercase() == kind
    }.map { it.second }

    private fun fieldExecutor(resolver: BaseBatchedFieldResolver) = FieldBatchResolverExecutorImpl(null, null, false, Provider { resolver }, "Query.batch", fieldFactory, "Query.batch")

    private fun unbatchedFieldExecutor(resolver: BaseUnbatchedFieldResolver) =
        FieldUnbatchedResolverExecutorImpl(null, null, false, Provider { resolver }, "Query.single", fieldFactory, "Query.single")

    private fun unbatchedNodeExecutor(resolver: BaseUnbatchedNodeResolver) = NodeUnbatchedResolverExecutorImpl(Provider { resolver }, "TestNode", nodeFactory, "TestNode", false)

    private fun fieldSelector() = FieldResolverExecutor.Selector(emptyMap(), null, { error("unused") }, { error("unused") })

    private fun nodeSelector(
        id: String,
        selections: String = "id"
    ) = NodeResolverExecutor.Selector(
        GlobalIDCodecDefault.serialize("TestNode", id),
        context.engineSelectionSetFactory.engineSelectionSet("TestNode", selections, emptyMap()),
    )
}
