@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.execution

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.dataloader.NextTickDispatcher
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime.FieldDataLoader
import viaduct.engine.runtime.mocks.ContextMocks

@ExperimentalCoroutinesApi
class Runtime2ScopedFieldBatchExecutorTest {
    @Test
    fun `cancelling one receiver preserves its batch sibling and ignores late delivery`() =
        withRequestScope { requestScope ->
            val batchStarted = CompletableDeferred<List<FieldResolverExecutor.Selector>>()
            val releaseBatch = CompletableDeferred<Unit>()
            val resolver = MockFieldBatchResolverExecutor(resolverId = "Query.value") { selectors, _ ->
                batchStarted.complete(selectors)
                releaseBatch.await()
                selectors.associateWith { Result.success(it.arguments["id"]) }
            }
            val loader = FieldDataLoader(Runtime2ScopedFieldBatchExecutor(resolver))
            val context = ContextMocks().engineExecutionContext
            val firstSelector = selector("A")
            val secondSelector = selector("B")
            var firstReturned = false
            val first = requestScope.async(start = CoroutineStart.UNDISPATCHED) {
                loader.loadByKey(firstSelector, context).getOrThrow().also { firstReturned = true }
            }
            val second = requestScope.async(start = CoroutineStart.UNDISPATCHED) {
                loader.loadByKey(secondSelector, context).getOrThrow()
            }
            assertEquals(setOf(firstSelector, secondSelector), batchStarted.await().toSet())

            first.cancelAndJoin()
            releaseBatch.complete(Unit)

            assertEquals("B", second.await())
            assertTrue(first.isCancelled)
            assertFalse(firstReturned)
        }

    @Test
    fun `request cancellation stops a shared batch callback and its receivers`() =
        withRequestScope { requestScope ->
            val batchStarted = CompletableDeferred<Int>()
            val batchStopped = CompletableDeferred<Unit>()
            val resolver = MockFieldBatchResolverExecutor(resolverId = "Query.value") { selectors, _ ->
                batchStarted.complete(selectors.size)
                try {
                    awaitCancellation()
                } finally {
                    batchStopped.complete(Unit)
                }
            }
            val loader = FieldDataLoader(Runtime2ScopedFieldBatchExecutor(resolver))
            val context = ContextMocks().engineExecutionContext
            val first = requestScope.async(start = CoroutineStart.UNDISPATCHED) { loader.loadByKey(selector("A"), context) }
            val second = requestScope.async(start = CoroutineStart.UNDISPATCHED) { loader.loadByKey(selector("B"), context) }
            assertEquals(2, batchStarted.await())

            requestScope.cancel()

            batchStopped.await()
            first.join()
            second.join()
            assertTrue(first.isCancelled)
            assertTrue(second.isCancelled)
        }

    @Test
    fun `batch children finish before loader results are delivered`() =
        withRequestScope { requestScope ->
            val batchReturned = CompletableDeferred<Unit>()
            val childStarted = CompletableDeferred<Unit>()
            val releaseChild = CompletableDeferred<Unit>()
            val resolver = MockFieldBatchResolverExecutor(resolverId = "Query.value") { selectors, _ ->
                CoroutineScope(currentCoroutineContext()).launch {
                    childStarted.complete(Unit)
                    releaseChild.await()
                }
                batchReturned.complete(Unit)
                selectors.associateWith { Result.success(it.arguments["id"]) }
            }
            val loader = FieldDataLoader(Runtime2ScopedFieldBatchExecutor(resolver))
            val context = ContextMocks().engineExecutionContext
            val result = requestScope.async(start = CoroutineStart.UNDISPATCHED) {
                loader.loadByKey(selector("A"), context).getOrThrow()
            }
            batchReturned.await()
            childStarted.await()
            // A released result must get a turn on the single-thread dispatcher before this check.
            yield()
            assertFalse(result.isCompleted)

            releaseChild.complete(Unit)

            assertEquals("A", result.await())
        }

    @Test
    fun `uncaught batch child failure reaches the request handler`() =
        withRequestScope { request ->
            val failure = IllegalStateException("child failed")
            val reported = CompletableDeferred<Throwable>()
            val handler = CoroutineExceptionHandler { _, cause ->
                reported.complete(cause)
                request.cancel()
            }
            val resolver = MockFieldBatchResolverExecutor(resolverId = "Query.value") { selectors, _ ->
                CoroutineScope(currentCoroutineContext() + handler).launch {
                    throw failure
                }
                selectors.associateWith { Result.success("unused") }
            }
            val loader = FieldDataLoader(Runtime2ScopedFieldBatchExecutor(resolver))
            val result = request.async(start = CoroutineStart.UNDISPATCHED) {
                loader.loadByKey(selector("A"), ContextMocks().engineExecutionContext)
            }

            assertSame(failure, reported.await())
            result.join()
            assertTrue(result.isCancelled)
        }

    private fun selector(id: String) =
        FieldResolverExecutor.Selector(
            arguments = mapOf("id" to id),
            selections = null,
            syncObjectValueGetter = { error("No object input is declared") },
            syncQueryValueGetter = { error("No Query input is declared") },
        )

    private fun withRequestScope(block: suspend (CoroutineScope) -> Unit) =
        runBlocking {
            withTimeout(5_000) {
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { wrappedDispatcher ->
                    val requestJob = Job()
                    val dispatcher = NextTickDispatcher(wrappedDispatcher, parentJob = requestJob)
                    try {
                        withContext(dispatcher) {
                            block(CoroutineScope(coroutineContext + requestJob))
                        }
                    } finally {
                        requestJob.cancelAndJoin()
                    }
                }
            }
        }
}
