@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldValue
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.internal.BaseBatchedFieldResolver
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.withInvocationContexts
import viaduct.errors.FrameworkException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory

@Suppress("UNUSED_PARAMETER")
class FieldBatchResolverExecutorImplTest {
    private val objectData = createEngineObjectData(MockSchema.minimal.schema.queryType, emptyMap())
    private val resolverContext = mockk<BaseFieldExecutionContext<*, *, *>>()
    private val resolverContextFactory = mockk<FieldExecutionContextFactory> {
        every {
            this@mockk.invoke(any(), any(), any(), any(), any(), any())
        } returns resolverContext
    }
    private val engineExecutionContext = ContextMocks().engineExecutionContext

    @Test
    fun `batchResolve throws when resolver returns non-list`() {
        val executor = createExecutor(TestBatchResolver("not-a-list"))

        val thrown = assertThrows<FrameworkException> {
            runBlocking {
                executor.batchResolve(listOf(selector()), engineExecutionContext)
            }
        }

        assertTrue(thrown.message!!.contains("Unexpected return value from batchResolve function"))
    }

    @Test
    fun `batchResolve throws when resolver returns wrong batch size`() {
        val executor = createExecutor(TestBatchResolver(listOf(FieldValue.ofValue("only-one"))))

        val thrown = assertThrows<TenantResolverException> {
            runBlocking {
                executor.batchResolve(listOf(selector(), selector()), engineExecutionContext)
            }
        }

        val cause = thrown.cause.shouldBeInstanceOf<TenantUsageException>()
        assertTrue(cause.message!!.contains("was given a batch of size 2 but returned 1 elements"))
        assertEquals("Query.batchedField", thrown.resolver)
    }

    @Test
    fun `batchResolve returns failure when batch item is not FieldValue`() {
        val selector = selector()
        val executor = createExecutor(TestBatchResolver(listOf("wrong-shape")))

        val result =
            runBlocking {
                executor.batchResolve(listOf(selector), engineExecutionContext)
            }

        val thrown = result.getValue(selector).exceptionOrNull().shouldBeInstanceOf<TenantResolverException>()
        val cause = thrown.cause.shouldBeInstanceOf<TenantUsageException>()
        assertTrue(cause.message!!.contains("Unexpected result type that is not a FieldValue"))
        assertEquals("Query.batchedField", thrown.resolver)
    }

    @Test
    fun `batchResolve preserves framework exception from error FieldValue`() {
        val selector = selector()
        val frameworkFailure = FrameworkException("framework failure")
        val executor = createExecutor(TestBatchResolver(listOf(FieldValue.ofError(frameworkFailure))))

        val result =
            runBlocking {
                executor.batchResolve(listOf(selector), engineExecutionContext)
            }

        assertSame(frameworkFailure, result.getValue(selector).exceptionOrNull())
    }

    @Test
    fun `batchResolve builds each tenant context from its selector invocation context`() {
        val firstSelector = selector()
        val secondSelector = selector()
        val firstContext = mockk<EngineExecutionContext> {
            every { requestContext } returns "first"
        }
        val secondContext = mockk<EngineExecutionContext> {
            every { requestContext } returns "second"
        }
        val batchContext =
            ContextMocks().engineExecutionContext.withInvocationContexts(
                mapOf(
                    firstSelector to firstContext,
                    secondSelector to secondContext,
                )
            )
        val capturedContexts = mutableListOf<EngineExecutionContext>()
        val factory = mockk<FieldExecutionContextFactory> {
            every {
                this@mockk.invoke(capture(capturedContexts), any(), any(), any(), any(), any())
            } returnsMany listOf(mockk(relaxed = true), mockk(relaxed = true))
        }
        val executor = FieldBatchResolverExecutorImpl(
            objectSelectionSet = null,
            querySelectionSet = null,
            isSelective = false,
            resolver = Provider { TestBatchResolver(listOf(FieldValue.ofValue("a"), FieldValue.ofValue("b"))) },
            resolverId = "Query.batchedField",
            resolverContextFactory = factory,
            resolverName = "Query.batchedField",
        )

        runBlocking {
            executor.batchResolve(listOf(firstSelector, secondSelector), batchContext)
        }

        assertEquals(listOf(firstContext, secondContext), capturedContexts)
    }

    private fun createExecutor(resolver: TestBatchResolver): FieldBatchResolverExecutorImpl =
        FieldBatchResolverExecutorImpl(
            objectSelectionSet = null,
            querySelectionSet = null,
            isSelective = false,
            resolver = Provider<BaseBatchedFieldResolver> { resolver },
            resolverId = "Query.batchedField",
            resolverContextFactory = resolverContextFactory,
            resolverName = "Query.batchedField",
        )

    private fun selector(): FieldResolverExecutor.Selector =
        FieldResolverExecutor.Selector(
            arguments = emptyMap(),
            selections = null,
            syncObjectValueGetter = { objectData },
            syncQueryValueGetter = { objectData },
        )

    class TestBatchResolver(
        private val result: Any?,
    ) : BaseBatchedFieldResolver {
        override suspend fun invokeFieldBatchResolver(contexts: List<BaseFieldExecutionContext<*, *, *>>): Any? {
            return result
        }
    }
}
