@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.bridge

import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.errors.ErroneousFieldException
import viaduct.errors.FieldError
import viaduct.errors.FrameworkException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.internal.BaseBatchedFieldResolver
import viaduct.java.api.resolvers.FieldValue

class JavaFieldBatchResolverExecutorTest {
    @Test
    fun `mixed outcomes retain selector identity and attribute only failed items`(): Unit =
        runBlocking {
            val failure = IllegalArgumentException("bad item")
            val executor = executor { contexts ->
                CompletableFuture.completedFuture(
                    linkedMapOf(
                        contexts[2] to FieldValue.ofError<Any?>(failure),
                        contexts[1] to FieldValue.ofValue(null),
                        contexts[0] to FieldValue.ofValue("ok"),
                    )
                )
            }
            val selectors = selectors(3)

            val results = executor.batchResolve(selectors, engineContext())

            assertEquals(selectors.toSet(), results.keys)
            assertEquals("ok", results.getValue(selectors[0]).getOrThrow())
            assertNull(results.getValue(selectors[1]).getOrThrow())
            val error = assertInstanceOf(TenantResolverException::class.java, results.getValue(selectors[2]).exceptionOrNull())
            assertSame(failure, error.cause)
            assertEquals("Item.value", error.resolver)
        }

    @Test
    fun `preexisting attribution and erroneous field details survive unwrapping`(): Unit =
        runBlocking {
            val failures = listOf(
                TenantResolverException(IllegalStateException("upstream"), "Item.upstream"),
                FrameworkException("framework"),
                ErroneousFieldException(listOf(FieldError("upstream", listOf("items", 0, "source"), mapOf("detail" to "retained")))),
            )
            val selectors = selectors(failures.size)
            val executor = executor { contexts ->
                CompletableFuture.completedFuture(contexts.zip(failures).associate { (ctx, failure) -> ctx to FieldValue.ofError<Any?>(failure) })
            }

            val results = executor.batchResolve(selectors, engineContext())

            selectors.zip(failures).forEach { (selector, failure) -> assertSame(failure, results.getValue(selector).exceptionOrNull()) }
        }

    @Test
    fun `tenant usage and independent item cancellation are attributed per item`(): Unit =
        runBlocking {
            for (failure in listOf(TenantUsageException("misuse"), CancellationException("item cancelled"))) {
                val executor = executor { contexts ->
                    CompletableFuture.completedFuture(mapOf(contexts[0] to FieldValue.ofValue("ok"), contexts[1] to FieldValue.ofError<Any?>(failure)))
                }
                val selectors = selectors(2)
                val results = executor.batchResolve(selectors, engineContext())
                assertEquals("ok", results.getValue(selectors[0]).getOrThrow())
                assertSame(failure, assertInstanceOf(TenantResolverException::class.java, results.getValue(selectors[1]).exceptionOrNull()).cause)
                assertTrue(currentCoroutineContext().isActive)
            }
        }

    @Test
    fun `null FieldValue is an item error rather than an explicit null`(): Unit =
        runBlocking {
            val executor = executor { contexts ->
                CompletableFuture.completedFuture(mapOf(contexts[0] to FieldValue.ofValue("ok"), contexts[1] to null))
            }
            val selectors = selectors(2)
            val results = executor.batchResolve(selectors, engineContext())
            assertEquals("ok", results.getValue(selectors[0]).getOrThrow())
            val error = assertInstanceOf(TenantResolverException::class.java, results.getValue(selectors[1]).exceptionOrNull())
            assertInstanceOf(TenantUsageException::class.java, error.cause)
        }

    @Test
    fun `malformed maps fail the entire invocation with tenant attribution`(): Unit =
        runBlocking {
            val foreign = mockk<FieldExecutionContext<*, *, *, *>>()
            val malformed: List<(List<FieldExecutionContext<*, *, *, *>>) -> Map<FieldExecutionContext<*, *, *, *>, FieldValue<*>?>?> = listOf(
                { null },
                { emptyMap() },
                { contexts -> mapOf(contexts[0] to FieldValue.ofValue("ok")) },
                { contexts -> mapOf(contexts[0] to FieldValue.ofValue("ok"), foreign to FieldValue.ofValue("foreign")) },
                { contexts -> contexts.associateWith { FieldValue.ofValue("ok") } + (foreign to FieldValue.ofValue("extra")) },
            )
            for (result in malformed) {
                val executor = executor { contexts -> CompletableFuture.completedFuture(result(contexts)) }
                val error = assertThrows<TenantResolverException> { executor.batchResolve(selectors(2), engineContext()) }
                assertInstanceOf(TenantUsageException::class.java, error.cause)
            }
        }

    @Test
    fun `failed and independently cancelled futures are tenant failures`(): Unit =
        runBlocking {
            val failure = IllegalStateException("batch failed")
            val failed = CompletableFuture.failedFuture<Map<FieldExecutionContext<*, *, *, *>, FieldValue<*>?>?>(failure)
            val cancelled = CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, FieldValue<*>?>?>().apply { cancel(false) }
            for (future in listOf(failed, cancelled)) {
                val error = assertThrows<TenantResolverException> { executor { future }.batchResolve(selectors(2), engineContext()) }
                if (future === failed) assertSame(failure, error.cause) else assertInstanceOf(CancellationException::class.java, error.cause)
                assertTrue(currentCoroutineContext().isActive)
            }
        }

    @Test
    fun `request cancellation propagates and cancels the pending bridge future`(): Unit =
        runBlocking {
            val future = CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, FieldValue<*>?>?>()
            val started = CompletableDeferred<Unit>()
            val executor = executor {
                started.complete(Unit)
                future
            }
            val request = async(start = CoroutineStart.UNDISPATCHED) { executor.batchResolve(selectors(1), engineContext()) }
            started.await()
            request.cancel()

            assertThrows<CancellationException> { request.await() }
            assertTrue(future.isCancelled)
        }

    @Test
    fun `old generated adapters continue to support values and explicit nulls`(): Unit =
        runBlocking {
            val resolver = BaseBatchedFieldResolver { contexts ->
                CompletableFuture.completedFuture(mapOf(contexts[0] to "legacy", contexts[1] to null))
            }
            val executor = FieldBatchResolverExecutorImpl(Provider { resolver }, "Item.value", "ValueResolver")
            val selectors = selectors(2)
            val results = executor.batchResolve(selectors, engineContext())
            assertEquals("legacy", results.getValue(selectors[0]).getOrThrow())
            assertNull(results.getValue(selectors[1]).getOrThrow())
            assertFalse(results.values.any { it.isFailure })
        }

    @Test
    fun `generated adapter translation rejects foreign and null keys and null maps`(): Unit =
        runBlocking {
            val context = mockk<FieldExecutionContext<*, *, *, *>>()
            val foreign = Any()
            for (returned in listOf<Map<Any?, String>?>(mapOf(foreign to "foreign"), mapOf(null to "null key"), null)) {
                val future = BaseBatchedFieldResolver.invokeBatch<Any?, String>(
                    listOf(context),
                    { Any() },
                    { CompletableFuture.completedFuture(returned) },
                )
                assertThrows<TenantUsageException> { future.await() }
            }
        }

    @Test
    fun `legacy adapter rejects a null map instead of wrapping it as a value`(): Unit =
        runBlocking {
            val resolver = BaseBatchedFieldResolver { CompletableFuture.completedFuture(null) }
            val executor = FieldBatchResolverExecutorImpl(Provider { resolver }, "Item.value", "ValueResolver")
            val error = assertThrows<TenantResolverException> { executor.batchResolve(selectors(1), engineContext()) }
            assertInstanceOf(TenantUsageException::class.java, error.cause)
        }

    private fun executor(resolve: (List<FieldExecutionContext<*, *, *, *>>) -> CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, FieldValue<*>?>?>): FieldBatchResolverExecutorImpl =
        FieldBatchResolverExecutorImpl(
            resolver = Provider {
                object : BaseBatchedFieldResolver {
                    override fun invokeFieldBatchResolver(contexts: List<FieldExecutionContext<*, *, *, *>>): CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, Any>> =
                        error("Legacy adapter must not be called")

                    override fun invokeFieldBatchResolverWithErrors(contexts: List<FieldExecutionContext<*, *, *, *>>): CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, FieldValue<*>?>?> =
                        resolve(contexts)
                }
            },
            resolverId = "Item.value",
            resolverName = "ValueResolver",
        )

    private fun selectors(count: Int) =
        (0 until count).map { index ->
            FieldResolverExecutor.Selector(
                arguments = mapOf("index" to index),
                selections = null,
                syncObjectValueGetter = { error("No object selections") },
                syncQueryValueGetter = { error("No query selections") },
            )
        }

    private fun engineContext(): EngineExecutionContext =
        mockk {
            every { requestContext } returns null
            every { fullSchema } returns mockk()
            every { globalIDCodec } returns mockk()
        }
}
