@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldValue
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.BaseBatchedFieldResolver
import viaduct.api.internal.BaseBatchedNodeResolver
import viaduct.api.internal.BaseUnbatchedFieldResolver
import viaduct.api.internal.BaseUnbatchedNodeResolver
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.withInvocationContexts
import viaduct.errors.TenantResolverException
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory

class BatchResolverMetricsTest {
    private val batches = mutableListOf<Pair<ResolverMetadata, Int>>()
    private val schema = MockSchema.mk("type TestNode { id: ID }")
    private val context = ContextMocks(myFullSchema = schema).engineExecutionContext
    private val state = object : ViaductResolverInstrumentation.InstrumentationState {}
    private val instrumentationContext = ResolverInstrumentationContext(
        object : ViaductResolverInstrumentation {
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
    fun `successful field batches record once at invocation entry including singletons`() =
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

            assertEquals(listOf(3, 1), batchSizes("Query.batch", "field"))
        }

    @Test
    fun `failed field batches record once before tenant code throws`() =
        runTest {
            val resolver = object : BaseBatchedFieldResolver {
                override suspend fun invokeFieldBatchResolver(contexts: List<BaseFieldExecutionContext<*, *, *>>): Any? {
                    assertEquals(listOf(2), batchSizes("Query.batch", "field"))
                    error("tenant failure")
                }
            }
            val executor = fieldExecutor(resolver)

            assertThrows<TenantResolverException> { executor.batchResolve(List(2) { fieldSelector() }, context, instrumentationContext) }

            assertEquals(listOf(2), batchSizes("Query.batch", "field"))
        }

    @Test
    fun `node dispatch records each partition of unique IDs including failed singleton`() =
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
            assertEquals(listOf(2, 1), batchSizes("TestNode", "node"))
        }

    @Test
    fun `context construction failure does not count as a resolver invocation`() =
        runTest {
            every { fieldFactory.invoke(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("context failure")
            val resolver = mockk<BaseBatchedFieldResolver>()

            assertThrows<IllegalStateException> {
                fieldExecutor(resolver).batchResolve(listOf(fieldSelector()), context, instrumentationContext)
            }

            assertTrue(batches.isEmpty())
        }

    @Test
    fun `unbatched field invocations do not record batch sizes`() =
        runTest {
            val resolver = object : BaseUnbatchedFieldResolver {
                override suspend fun invokeFieldResolver(context: BaseFieldExecutionContext<*, *, *>): Any? = "value"
            }
            val executor = unbatchedFieldExecutor(resolver)

            val result = executor.batchResolve(listOf(fieldSelector()), context, instrumentationContext)

            assertTrue(result.values.single().isSuccess)
            assertTrue(batches.isEmpty())
        }

    @Test
    fun `failed unbatched node invocations do not record batch sizes`() =
        runTest {
            val resolver = object : BaseUnbatchedNodeResolver {
                override suspend fun invokeNodeResolver(context: NodeExecutionContext<*>): Any? = error("unbatched failure")
            }
            val executor = unbatchedNodeExecutor(resolver)

            val result = executor.resolve(listOf(nodeSelector("a")), context, instrumentationContext)

            assertTrue(result.values.single().isFailure)
            assertTrue(batches.isEmpty())
        }

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
