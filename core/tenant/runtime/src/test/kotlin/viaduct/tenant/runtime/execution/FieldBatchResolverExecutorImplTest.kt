@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldValue
import viaduct.api.ResolverBase
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.errors.FrameworkException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory

class FieldBatchResolverExecutorImplTest {
    private val objectData = createEngineObjectData(MockSchema.minimal.schema.queryType, emptyMap())
    private val resolverContext = mockk<BaseFieldExecutionContext<*, *, *>>()
    private val resolverContextFactory = mockk<FieldExecutionContextFactory> {
        every {
            this@mockk.invoke(any(), any(), any(), any(), any(), any(), any(), any())
        } returns resolverContext
    }
    private val engineExecutionContext = mockk<EngineExecutionContext> {
        every { requestContext } returns null
        every { globalIDCodec } returns GlobalIDCodecDefault
    }

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

        val cause = assertInstanceOf(TenantUsageException::class.java, thrown.cause)
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

        val thrown = assertInstanceOf(TenantResolverException::class.java, result.getValue(selector).exceptionOrNull())
        val cause = assertInstanceOf(TenantUsageException::class.java, thrown.cause)
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

    private fun createExecutor(resolver: TestBatchResolver): FieldBatchResolverExecutorImpl =
        FieldBatchResolverExecutorImpl(
            objectSelectionSet = null,
            querySelectionSet = null,
            isSelective = false,
            resolver = Provider<ResolverBase<*>> { resolver },
            batchResolveFn = TestBatchResolver::batchResolve,
            resolverId = "Query.batchedField",
            reflectionLoader = MockReflectionLoader(),
            resolverContextFactory = resolverContextFactory,
            resolverName = "Query.batchedField",
        )

    private fun selector(): FieldResolverExecutor.Selector =
        FieldResolverExecutor.Selector(
            arguments = emptyMap(),
            objectValue = objectData,
            queryValue = objectData,
            selections = null,
        )

    class TestBatchResolver(
        private val result: Any?,
    ) : ResolverBase<Any?> {
        @Suppress("unused")
        suspend fun batchResolve(contexts: List<Any?>): Any? {
            return result
        }
    }
}
