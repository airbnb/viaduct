@file:Suppress("ForbiddenImport")

package viaduct.deferred

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

class DeferredExtensionsTest {
    @Nested
    inner class ParentingCompletableDeferredTests {
        @Test
        fun `active parent - deferred is parented via SupervisorJob and supervisor completes on child completion`() =
            runBlocking {
                val parent = Job()
                lateinit var child: CompletableDeferred<Int>
                lateinit var supervisor: Job

                withThreadLocalParent(parent) {
                    val before = parent.children.toSet()

                    child = completableDeferred()
                    yield()

                    val after = parent.children.toSet()
                    val newChildren = after - before
                    assertEquals(1, newChildren.size, "Exactly one new child (the supervisor) should be added")
                    supervisor = newChildren.single()

                    // The supervisor should have the completable deferred as its child
                    assertTrue(
                        supervisor.children.any { it === child },
                        "Supervisor should have the returned deferred as its child"
                    )

                    // Complete the child; supervisor should complete (not cancel)
                    child.complete(42)
                }

                yield()
                assertTrue(supervisor.isCompleted, "Supervisor should be completed after child completes")
                assertFalse(supervisor.isCancelled, "Supervisor should complete normally, not be cancelled")
                assertTrue(parent.isActive, "Parent should remain active")
            }

        @Test
        fun `active parent - cancelling child cancels result but completes supervisor (not cancelled)`() =
            runBlocking {
                val parent = Job()
                lateinit var child: CompletableDeferred<Unit>
                lateinit var supervisor: Job

                withThreadLocalParent(parent) {
                    val before = parent.children.toSet()

                    child = completableDeferred()
                    yield()

                    val after = parent.children.toSet()
                    val newChildren = after - before
                    supervisor = newChildren.single()

                    val cancel = CancellationException("test-cancel")
                    child.cancel(cancel)
                }

                yield()
                assertTrue(child.isCancelled, "Child should be cancelled")
                assertTrue(supervisor.isCompleted, "Supervisor should complete on child cancellation")
                assertFalse(supervisor.isCancelled, "Supervisor should NOT be cancelled")
                assertTrue(parent.isActive, "Parent remains active")
            }

        @Test
        fun `parent cancellation cascades to deferred`() =
            runBlocking {
                val parent = Job()
                lateinit var d: CompletableDeferred<Int>

                // Create and link under the TL parent, then exit the scope
                withThreadLocalParent(parent) {
                    val before = parent.children.toSet()

                    d = completableDeferred()

                    // best-effort: ensure linkage happened
                    yield()

                    val after = parent.children.toSet()
                    val newChildren = after - before

                    // Optional sanity (don’t hard-fail if you don’t want flakiness):
                    assertTrue(
                        newChildren.any { sup -> sup.children.any { it === d } },
                        "Supervisor should parent the deferred"
                    )
                }

                // Now we're outside the TL scope; cancelling parent won't cancel the test coroutine
                val ce = CancellationException("parent-cancel")
                parent.cancel(ce)

                // Observe cancellation of d
                val observed = CompletableDeferred<CancellationException>()
                d.invokeOnCompletion { cause ->
                    if (cause is CancellationException) observed.complete(cause)
                }

                val got = withTimeout(2_000) { observed.await() }
                assertEquals("parent-cancel", got.message)
                assertTrue(d.isCancelled, "Deferred should be cancelled by parent cancellation")
            }

        @Test
        fun `inactive parent - deferred is NOT parented`() =
            runBlocking {
                val inactiveParent = Job().apply { cancel(CancellationException("inactive")) }
                lateinit var d: CompletableDeferred<Int>
                // Install the inactive parent as the TL default job
                ThreadLocalCoroutineContextManager.INSTANCE.setDefaultCoroutineContext(inactiveParent)

                // Install only the TL ContextElement; do NOT include the cancelled Job in the coroutine context.
                coroutineScope {
                    d = completableDeferred() // this is going to get the inactive parent as the parent
                    yield()

                    // Because parent.isActive == false, completableDeferred() should have returned an unparented deferred.
                    assertTrue(
                        inactiveParent.children.toList().isEmpty(),
                        "No supervisor should be created when parent is inactive"
                    )

                    d.complete(1)
                }

                assertTrue(d.isCompleted)
                assertEquals(1, d.await())
            }

        @Test
        fun `no thread-local job - deferred is NOT parented`() =
            runBlocking {
                // Ensure no thread-local ContextElement is installed here.
                // Calling completableDeferred() should create an unparented deferred.
                val d = completableDeferred<Int>()
                d.complete(7)
                assertTrue(d.isCompleted)

                // We can't directly assert "no parent" from the API, but we can at least
                // assert that creating it did not accidentally produce a dangling parent job
                // (no exceptions, and completion works fine).
                assertEquals(7, d.await())
            }

        @Test
        fun `parenting chain remains stable across success and failure`() =
            runBlocking {
                val parent = Job()
                lateinit var ok: CompletableDeferred<String>
                lateinit var bad: CompletableDeferred<String>
                lateinit var supOk: Job
                lateinit var supBad: Job

                withThreadLocalParent(parent) {
                    val before = parent.children.toSet()

                    ok = completableDeferred()
                    bad = completableDeferred()
                    yield()

                    val after = parent.children.toSet()
                    val newChildren = after - before
                    // We created exactly two supervisors for ok & bad
                    assertEquals(2, newChildren.size, "Parent should have two new supervisor children")

                    // Identify which supervisor owns which child
                    val byChild = newChildren.associateWith { sup -> sup.children.single() }
                    supOk = byChild.entries.first { it.value === ok }.key
                    supBad = byChild.entries.first { it.value === bad }.key

                    ok.complete("yay")
                    bad.completeExceptionally(IllegalStateException("boom"))
                }

                yield()
                assertTrue(supOk.isCompleted && !supOk.isCancelled, "supOk should be completed, not cancelled")
                assertTrue(supBad.isCompleted && !supBad.isCancelled, "supBad should be completed, not cancelled")
                assertTrue(parent.isActive)
            }

        /**
         * Installs a thread-local coroutine context with the provided [parent] as the default Job
         * for the duration of [block]. This ensures completableDeferred() can see the parent via
         * ThreadLocalCoroutineContextManager.ContextElement without touching global defaults.
         */
        private suspend fun <R> withThreadLocalParent(
            parent: Job,
            block: suspend () -> R
        ): R {
            val ctx = parent + ThreadLocalCoroutineContextManager.ContextElement(defaultJob = parent)
            return withContext(ctx) { block() }
        }
    }

    @Nested
    inner class CompletedDeferredTests {
        @Test
        fun `completedDeferred returns a completed deferred with the correct value`() {
            runBlocking {
                val value = 42
                val deferred = completedDeferred(value)
                assertTrue(deferred.isCompleted)
                assertEquals(value, deferred.await())
            }
        }

        @Test
        fun `completedDeferred works with a null value`() {
            runBlocking {
                val deferred = completedDeferred<String?>(null)
                assertTrue(deferred.isCompleted)
                assertNull(deferred.await())
            }
        }
    }

    @Nested
    inner class ExceptionalDeferredTests {
        @Test
        fun `exceptionalDeferred returns a deferred completed exceptionally`() {
            runBlocking {
                val err = RuntimeException()
                val deferred = exceptionalDeferred<Int>(err)
                assertThrows<RuntimeException> { deferred.await() }
            }
        }
    }

    @Nested
    inner class HandleIfDeferredTests {
        @Test
        fun `handleIfDeferred calls handler for immediate value`() {
            val result = handle({ 5 }) { value, throwable ->
                if (throwable != null) "error" else value.toString()
            }
            assertEquals("5", result)
        }

        @Test
        fun `handleIfDeferred calls handler for exception thrown in block`() {
            val exception = IllegalArgumentException("oops")
            val result = handle<Int>({ throw exception }) { value, throwable ->
                throwable?.message ?: value.toString()
            }
            assertEquals("oops", result)
        }

        @Test
        fun `handleIfDeferred handles deferred returned from block`() {
            runBlocking {
                val deferred = completedDeferred(10)
                val result = handle({ deferred }) { value, throwable ->
                    if (throwable != null) "error" else (value as Int * 2).toString()
                }
                // When a block returns a Deferred, handleIfDeferred returns a Deferred.
                @Suppress("UNCHECKED_CAST")
                val finalResult = (result as Deferred<*>).await()
                assertEquals("20", finalResult)
            }
        }

        @Test
        fun `handleIfDeferred handles deferred exception`() {
            runBlocking {
                val exception = RuntimeException("fail")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(exception)
                val result = handle({ failedDeferred }) { _, throwable ->
                    throwable?.message ?: "no error"
                }

                @Suppress("UNCHECKED_CAST")
                val finalResult = (result as Deferred<*>).await()
                assertEquals("fail", finalResult)
            }
        }
    }

    @Nested
    inner class DeferredHandleIfDeferredExtensionTests {
        @Test
        fun `handleIfDeferred returns transformed result on normal completion`() {
            runBlocking {
                val deferred = completedDeferred(3)
                val transformed = deferred.handle { value, throwable ->
                    if (throwable != null) -1 else value!! * 2
                }
                assertEquals(6, transformed.await())
            }
        }

        @Test
        fun `handleIfDeferred returns fallback result on exception`() {
            runBlocking {
                val exception = IllegalStateException("error")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(exception)
                val handled = failedDeferred.handle { value, throwable ->
                    if (throwable != null) 0 else value!!
                }
                assertEquals(0, handled.await())
            }
        }
    }

    @Nested
    inner class HandleFastSlow {
        @Test
        fun `handle FAST success`() =
            runBlocking {
                val d = completedDeferred(10) // already complete -> fast path
                val res = d.handle { v, e ->
                    require(e == null)
                    v!! * 3
                }
                assertEquals(30, res.await())
            }

        @Test
        fun `handle SLOW success`() =
            runBlocking {
                val src = completableDeferred<Int>() // not complete -> slow path
                val res = src.handle { v, e ->
                    require(e == null)
                    v!! + 1
                }
                src.complete(41) // complete after handler is registered
                assertEquals(42, res.await())
            }

        @Test
        fun `handle FAST failure`() =
            runBlocking {
                val err = IllegalStateException("boom")
                val d = completableDeferred<Int>().apply { completeExceptionally(err) } // fast path exceptional
                val res = d.handle { _, e -> if (e != null) 0 else 1 }
                assertEquals(0, res.await())
            }

        @Test
        fun `handle SLOW failure`() =
            runBlocking {
                val src = completableDeferred<Int>()
                val res = src.handle { _, e -> if (e != null) 7 else 0 }
                src.completeExceptionally(IllegalArgumentException("x"))
                assertEquals(7, res.await())
            }
    }

    @Nested
    inner class ThenApplyTests {
        @Test
        fun `thenApply transforms completed deferred value`() {
            runBlocking {
                val deferred = completedDeferred(5)
                val transformed = deferred.thenApply { it * 3 }
                assertEquals(15, transformed.await())
            }
        }

        @Test
        fun `thenApply propagates exception from original deferred`() {
            runBlocking {
                val exception = RuntimeException("oops")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(exception)
                val transformed = failedDeferred.thenApply { it * 3 }
                val thrown = assertThrows<RuntimeException> { runBlocking { transformed.await() } }
                assertEquals("oops", thrown.message)
            }
        }

        @Test
        fun `thenApply propagates exception thrown in transform function`() {
            runBlocking {
                val deferred = completedDeferred(10)
                val exception = IllegalArgumentException("bad transform")
                val transformed = deferred.thenApply { throw exception }
                val thrown = assertThrows<IllegalArgumentException> { runBlocking { transformed.await() } }
                assertEquals("bad transform", thrown.message)
            }
        }
    }

    @Nested
    inner class ThenApplyFastSlow {
        @Test
        fun `thenApply FAST success`() =
            runBlocking {
                val d = completedDeferred(5)
                val t = d.thenApply { it * 4 }
                assertEquals(20, t.await())
            }

        @Test
        fun `thenApply SLOW success`() =
            runBlocking {
                val src = completableDeferred<Int>()
                val t = src.thenApply { it + 2 }
                src.complete(40)
                assertEquals(42, t.await())
            }

        @Test
        fun `thenApply FAST failure propagates`() =
            runBlocking {
                val err = RuntimeException("oops")
                val d = completableDeferred<Int>().apply { completeExceptionally(err) }
                val t = d.thenApply { it * 2 }
                val thrown = assertThrows<RuntimeException> { runBlocking { t.await() } }
                assertEquals("oops", thrown.message)
            }
    }

    @Nested
    inner class ThenComposeTests {
        @Test
        fun `thenCompose flattens deferreds successfully`() {
            runBlocking {
                val deferred = completedDeferred(4)
                val composed = deferred.thenCompose { value ->
                    completedDeferred(value * 2)
                }
                assertEquals(8, composed.await())
            }
        }

        @Test
        fun `thenCompose propagates exception from original deferred`() {
            runBlocking {
                val exception = RuntimeException("error in original")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(exception)
                val composed = failedDeferred.thenCompose { value ->
                    completedDeferred(value * 2)
                }
                val thrown = assertThrows<RuntimeException> { runBlocking { composed.await() } }
                assertEquals("error in original", thrown.message)
            }
        }

        @Test
        fun `thenCompose propagates exception from inner deferred`() {
            runBlocking {
                val deferred = completedDeferred(5)
                val innerException = IllegalStateException("inner error")
                val composed = deferred.thenCompose { _ ->
                    val inner = completableDeferred<Int>()
                    inner.completeExceptionally(innerException)
                    inner
                }
                val thrown = assertThrows<IllegalStateException> { runBlocking { composed.await() } }
                assertEquals("inner error", thrown.message)
            }
        }

        @Test
        fun `outer cancelled - returned deferred is cancelled (not exceptional)`() =
            runBlocking {
                val outer = completableDeferred<Int>()
                val result = outer.thenCompose { completableDeferred<String>() } // won't be called
                val cancel = CancellationException("outer-cancelled")
                outer.cancel(cancel)

                val ex = assertThrows<CancellationException> { result.await() }
                assertEquals("outer-cancelled", ex.message)
            }

        @Test
        fun `fn throws - CancellationException cancels, other exceptions completeExceptionally`() =
            runBlocking {
                // CancellationException path
                run {
                    val outer = completableDeferred<Int>()
                    val res = outer.thenCompose<Int, Unit> {
                        throw CancellationException("fn-cancel")
                    }
                    outer.complete(1)
                    val ex = assertThrows<CancellationException> { res.await() }
                    assertEquals("fn-cancel", ex.message)
                }

                // Non-cancellation exception path
                run {
                    val outer = completableDeferred<Int>()
                    val res = outer.thenCompose<Int, Unit> {
                        throw IllegalArgumentException("fn-arg")
                    }
                    outer.complete(1)
                    val ex = assertThrows<IllegalArgumentException> { res.await() }
                    assertEquals("fn-arg", ex.message)
                }
            }

        @Test
        fun `cancelling returned deferred cancels inner (no leak)`() =
            runBlocking {
                val outer = completableDeferred<Int>()
                val innerCancelled = completableDeferred<CancellationException>()

                val result = outer.thenCompose {
                    completableDeferred<String>().also { inner ->
                        // observe cancellation explicitly
                        inner.invokeOnCompletion { cause ->
                            if (cause is CancellationException) {
                                innerCancelled.complete(cause)
                            }
                        }
                    }
                }

                outer.complete(7)
                // Ensure inner exists
                yield()

                val cancel = CancellationException("caller-cancelled")
                result.cancel(cancel)

                val observed = innerCancelled.await()
                assertEquals("caller-cancelled", observed.message)

                // Returned deferred is cancelled (not exceptional)
                val ex = assertThrows<CancellationException> { result.await() }
                assertEquals("caller-cancelled", ex.message)
            }

        @Test
        fun `inner cancellation cancels returned deferred`() =
            runBlocking {
                val outer = completableDeferred<Int>()
                val inner = completableDeferred<String>()

                val result = outer.thenCompose { inner }

                outer.complete(123)
                // cancel inner after linkage
                yield()
                val cancel = CancellationException("inner-cancel")
                inner.cancel(cancel)

                val ex = assertThrows<CancellationException> { result.await() }
                assertEquals("inner-cancel", ex.message)
            }

        @Test
        fun `fn not called when outer fails or cancels`() =
            runBlocking {
                // outer exceptional
                run {
                    val called = AtomicBoolean(false)
                    val outer = completableDeferred<Int>()
                    val result = outer.thenCompose {
                        called.set(true)
                        completableDeferred<String>().apply { complete("x") }
                    }
                    outer.completeExceptionally(IllegalStateException("fail"))
                    assertTrue(result.isCompleted)
                    assertFalse(called.get())
                }
                // outer cancel
                run {
                    val called = AtomicBoolean(false)
                    val outer = completableDeferred<Int>()
                    val result = outer.thenCompose {
                        called.set(true)
                        completableDeferred<String>().apply { complete("x") }
                    }
                    outer.cancel(CancellationException("bye"))
                    assertTrue(result.isCompleted)
                    assertFalse(called.get())
                }
            }

        @Test
        fun `defensive getCompleted guards - simulate odd throws`() =
            runBlocking {
                // This is synthetic; in practice getCompleted() shouldn't throw here
                // but we can still check that such a throw cancels vs completes exceptionally.
                val d = object : CompletableDeferred<Int> by completableDeferred() {
                    override fun getCompleted(): Int {
                        throw IllegalStateException("weird")
                    }
                }
                val res = d.thenCompose { completableDeferred<String>().apply { complete("ok") } }
                d.complete(1)

                val ex = assertThrows<IllegalStateException> { res.await() }
                assertEquals("weird", ex.message)
            }
    }

    @Nested
    inner class ThenComposeFastSlow {
        @Test
        fun `thenCompose FAST success returns inner`() =
            runBlocking {
                val outer = completedDeferred(3) // fast path
                val inner = completableDeferred<Int>().apply { complete(9) }
                val composed = outer.thenCompose { inner }
                assertEquals(9, composed.await())
            }

        @Test
        fun `thenCompose FAST cancel of returned cancels inner (if returned directly)`() =
            runBlocking {
                val outer = completedDeferred(1)
                val inner = completableDeferred<String>()
                val composed = outer.thenCompose { inner } // fast path returns inner directly in your impl
                val cancel = CancellationException("caller-cancel")
                composed.cancel(cancel)
                val ex = assertThrows<CancellationException> { inner.await() }
                assertEquals("caller-cancel", ex.message)
            }

        @Test
        fun `thenCompose SLOW success`() =
            runBlocking {
                val outer = completableDeferred<Int>()
                val composed = outer.thenCompose { v -> completedDeferred(v * 5) }
                outer.complete(2)
                assertEquals(10, composed.await())
            }

        @Test
        fun `thenCompose SLOW outer failure`() =
            runBlocking {
                val outer = completableDeferred<Int>()
                val composed = outer.thenCompose { completedDeferred(it) }
                outer.completeExceptionally(IllegalStateException("fail"))
                val ex = assertThrows<IllegalStateException> { composed.await() }
                assertEquals("fail", ex.message)
            }
    }

    @Nested
    inner class ExceptionallyTests {
        @Test
        fun `exceptionally returns original value if deferred completes normally`() {
            runBlocking {
                val deferred = completedDeferred(7)
                val handled = deferred.exceptionally { _ -> 0 }
                assertEquals(7, handled.await())
            }
        }

        @Test
        fun `exceptionally returns fallback value if deferred fails`() {
            runBlocking {
                val exception = RuntimeException("fail")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(exception)
                val handled = failedDeferred.exceptionally { ex ->
                    assertEquals("fail", ex.message)
                    42
                }
                assertEquals(42, handled.await())
            }
        }

        @Test
        fun `exceptionally propagates exception if fallback throws exception`() {
            runBlocking {
                val exception = RuntimeException("original")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(exception)
                val fallbackException = IllegalArgumentException("fallback error")
                val handled = failedDeferred.exceptionally { _ ->
                    throw fallbackException
                }
                val thrown = assertThrows<IllegalArgumentException> { runBlocking { handled.await() } }
                assertEquals("fallback error", thrown.message)
            }
        }
    }

    @Nested
    inner class ExceptionallyFastSlow {
        @Test
        fun `exceptionally FAST success returns original`() =
            runBlocking {
                val d = completedDeferred(7)
                val h = d.exceptionally { 0 }
                assertEquals(7, h.await())
            }

        @Test
        fun `exceptionally SLOW failure returns fallback`() =
            runBlocking {
                val src = completableDeferred<Int>()
                val h = src.exceptionally { 42 }
                src.completeExceptionally(RuntimeException("x"))
                assertEquals(42, h.await())
            }

        @Test
        fun `exceptionally FAST cancellation is propagated (no recovery)`() =
            runBlocking {
                val d = completableDeferred<Int>().apply { cancel(CancellationException("bye")) }
                val h = d.exceptionally { 1 }
                val ex = assertThrows<CancellationException> { h.await() }
                assertEquals("bye", ex.message)
            }
    }

    @Nested
    inner class ExceptionallyComposeTests {
        @Test
        fun `exceptionallyCompose returns original value if deferred completes normally`() {
            runBlocking {
                val deferred = completedDeferred(100)
                val result: Deferred<Int> = deferred.exceptionallyCompose { _ ->
                    // fallback should never be called when deferred completes normally
                    completedDeferred(0)
                }
                assertEquals(100, result.await())
            }
        }

        @Test
        fun `exceptionallyCompose returns fallback value if deferred fails`() {
            runBlocking {
                val originalException = RuntimeException("original failure")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(originalException)
                val result = failedDeferred.exceptionallyCompose { ex ->
                    // Optionally, verify that the exception passed to fallback is the original one
                    assertEquals("original failure", ex.message)
                    completedDeferred(42)
                }
                assertEquals(42, result.await())
            }
        }

        @Test
        fun `exceptionallyCompose propagates exception if fallback deferred fails`() {
            runBlocking {
                val originalException = RuntimeException("original failure")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(originalException)
                val fallbackException = IllegalStateException("fallback failure")
                val result = failedDeferred.exceptionallyCompose { _ ->
                    val fallback = completableDeferred<Int>()
                    fallback.completeExceptionally(fallbackException)
                    fallback
                }
                val thrown = assertThrows<IllegalStateException> { runBlocking { result.await() } }
                assertEquals("fallback failure", thrown.message)
            }
        }

        @Test
        fun `exceptionallyCompose propagates exception if fallback function throws exception`() {
            runBlocking {
                val originalException = RuntimeException("original failure")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(originalException)
                val fallbackException = IllegalArgumentException("fallback function exception")
                val result = failedDeferred.exceptionallyCompose<Int> { _ ->
                    throw fallbackException
                }
                val thrown = assertThrows<IllegalArgumentException> { runBlocking { result.await() } }
                assertEquals("fallback function exception", thrown.message)
            }
        }

        @Test
        fun `does NOT recover cancellation - outer cancel cancels result, fallback NOT called`() =
            runBlocking {
                val outer = completableDeferred<Int>()
                val called = AtomicBoolean(false)

                val result = outer.exceptionallyCompose { _ ->
                    called.set(true)
                    completedDeferred(999)
                }

                val cancel = CancellationException("outer-cancelled")
                outer.cancel(cancel)

                val ex = assertThrows<CancellationException> { result.await() }
                assertEquals("outer-cancelled", ex.message)
                assertFalse(called.get(), "fallback should not be invoked on cancellation")
            }

        @Test
        fun `caller cancels returned deferred - cancels fallback (no leak)`() =
            runBlocking {
                val outer = completableDeferred<Int>()
                val fallbackCancelled = CompletableDeferred<CancellationException>()

                val result = outer.exceptionallyCompose { cause ->
                    // long-lived fallback; record its cancellation
                    completableDeferred<Int>().also { fb ->
                        fb.invokeOnCompletion { c ->
                            if (c is CancellationException) fallbackCancelled.complete(c)
                        }
                    }
                }

                // Ensure we go down the fallback path
                outer.completeExceptionally(IllegalStateException("boom"))
                // Give time for fallback to be created and linked
                yield()

                val cancel = CancellationException("caller-cancelled")
                result.cancel(cancel)

                val observed = fallbackCancelled.await()
                assertEquals("caller-cancelled", observed.message)

                val ex = assertThrows<CancellationException> { result.await() }
                assertEquals("caller-cancelled", ex.message)
            }

        @Test
        fun `fallback deferred is cancelled - result is cancelled`() =
            runBlocking {
                val outer = completableDeferred<Int>()

                val result = outer.exceptionallyCompose { _ ->
                    completableDeferred<Int>().also { fb ->
                        // cancel fallback after creation
                        fb.cancel(CancellationException("fallback-cancelled"))
                    }
                }

                outer.completeExceptionally(RuntimeException("fail"))

                val ex = assertThrows<CancellationException> { result.await() }
                assertEquals("fallback-cancelled", ex.message)
            }

        @Test
        fun `fallback function throws CancellationException - result is cancelled`() =
            runBlocking {
                val outer = completableDeferred<Int>()

                val result = outer.exceptionallyCompose<Int> {
                    throw CancellationException("fallback-fn-cancel")
                }

                outer.completeExceptionally(IllegalStateException("boom"))
                val ex = assertThrows<CancellationException> { result.await() }
                assertEquals("fallback-fn-cancel", ex.message)
            }

        @Test
        fun `defensive getCompleted guard on success path - weird throw completesExceptionally or cancels`() =
            runBlocking {
                // Synthetic: getCompleted throws even though completion was "successful"
                val d = object : CompletableDeferred<Int> by CompletableDeferred() {
                    override fun getCompleted(): Int {
                        throw IllegalStateException("weird-getCompleted")
                    }
                }

                val res = d.exceptionallyCompose { _ ->
                    // should not run; outer "success" branch is taken
                    completedDeferred(0)
                }
                d.complete(123)

                val ex = assertThrows<IllegalStateException> { res.await() }
                assertEquals("weird-getCompleted", ex.message)
            }

        @Test
        fun `fallback invoked exactly once`() =
            runBlocking {
                val outer = completableDeferred<Int>()
                val calls = AtomicInteger(0)

                val res = outer.exceptionallyCompose {
                    calls.incrementAndGet()
                    completedDeferred(1)
                }

                outer.completeExceptionally(IllegalStateException("boom"))
                assertEquals(1, res.await())
                assertEquals(1, calls.get(), "fallback must be called exactly once")
            }
    }

    @Nested
    inner class ExceptionallyComposeFastSlow {
        @Test
        fun `exceptionallyCompose FAST success returns original`() =
            runBlocking {
                val d = completedDeferred(100)
                val r = d.exceptionallyCompose { completedDeferred(0) }
                assertEquals(100, r.await())
            }

        @Test
        fun `exceptionallyCompose FAST failure uses fallback`() =
            runBlocking {
                val d = completableDeferred<Int>().apply { completeExceptionally(RuntimeException("boom")) }
                val r = d.exceptionallyCompose { ex ->
                    assertEquals("boom", ex.message)
                    completedDeferred(5)
                }
                assertEquals(5, r.await())
            }

        @Test
        fun `exceptionallyCompose SLOW failure uses fallback`() =
            runBlocking {
                val src = completableDeferred<Int>()
                val r = src.exceptionallyCompose { completedDeferred(9) }
                src.completeExceptionally(IllegalStateException("x"))
                assertEquals(9, r.await())
            }

        @Test
        fun `exceptionallyCompose FAST cancellation propagates (fallback not called)`() =
            runBlocking {
                val d = completableDeferred<Int>().apply { cancel(CancellationException("c")) }
                var called = false
                val r = d.exceptionallyCompose {
                    called = true
                    completedDeferred(1)
                }
                val ex = assertThrows<CancellationException> { r.await() }
                assertEquals("c", ex.message)
                assertFalse(called)
            }
    }

    @Nested
    @DelicateCoroutinesApi
    inner class ThenCombineTests {
        @Test
        fun `thenCombine combines two successful deferred values`() {
            runBlocking {
                val deferred1 = completedDeferred(3)
                val deferred2 = completedDeferred(4)
                val combined = deferred1.thenCombine(deferred2) { a, b -> a + b }
                assertEquals(7, combined.await())
            }
        }

        @Test
        fun `thenCombine propagates exception if first deferred fails`() {
            runBlocking {
                val exception = RuntimeException("first error")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(exception)
                val deferred2 = completedDeferred(4)
                val combined = failedDeferred.thenCombine(deferred2) { a, b -> a + b }
                val thrown = assertThrows<RuntimeException> { runBlocking { combined.await() } }
                assertEquals("first error", thrown.message)
            }
        }

        @Test
        fun `thenCombine propagates exception if second deferred fails`() {
            runBlocking {
                val deferred1 = completedDeferred(3)
                val exception = RuntimeException("second error")
                val failedDeferred = completableDeferred<Int>()
                failedDeferred.completeExceptionally(exception)
                val combined = deferred1.thenCombine(failedDeferred) { a, b -> a + b }
                val thrown = assertThrows<RuntimeException> { runBlocking { combined.await() } }
                assertEquals("second error", thrown.message)
            }
        }

        @Test
        fun `thenCombine propagates exception if combiner throws exception`() {
            runBlocking {
                val deferred1 = completedDeferred(3)
                val deferred2 = completedDeferred(4)
                val exception = IllegalStateException("combiner error")
                val combined = deferred1.thenCombine(deferred2) { _, _ -> throw exception }
                val thrown = assertThrows<IllegalStateException> { runBlocking { combined.await() } }
                assertEquals("combiner error", thrown.message)
            }
        }

        /**
         * This test ensures that [DeferredExtensions.thenCombine] is thread-safe and always completes,
         * even if one of the deferreds fails.
         */
        @RepeatedTest(10)
        fun `amplify concurrency for thenCombine to test race conditions`() {
            runBlocking {
                val NUM_PAIRS = 1000 // Increase for more stress
                val allCombines = mutableListOf<Deferred<Int>>()

                // A latch so that none of the Deferreds complete until we say "go!"
                val latch = CountDownLatch(1)

                repeat(NUM_PAIRS) {
                    val left = GlobalScope.async {
                        // Wait for the latch to ensure a mass-release
                        latch.await()
                        // Optional random delay to further scramble timings
                        delay(Random.nextLong(0, 5))
                        if (Random.nextInt(4) == 0) {
                            throw IllegalStateException("Left side fail")
                        }
                        1
                    }
                    val right = GlobalScope.async {
                        latch.await()
                        delay(Random.nextLong(0, 5))
                        if (Random.nextInt(4) == 0) {
                            throw IllegalStateException("Right side fail")
                        }
                        2
                    }
                    // Wire them up with oldThenCombine
                    val combined = left.thenCombine(right) { a, b -> a + b }
                    allCombines += combined
                }

                // Now release them all at once:
                latch.countDown()

                // We'll wait for them to finish; if one gets stuck => test eventually times out.
                // We don't care if it's a success or an exception - just that none "hang".
                allCombines.forEachIndexed { i, d ->
                    try {
                        withTimeout(1000) { d.await() }
                        // Possibly println("Pair $i succeeded: ${res}")
                    } catch (ex: TimeoutCancellationException) {
                        fail("Pair $i hung with thenCombine, big concurrency scenario!")
                    } catch (_: Throwable) {
                        // We expect random fails here, that's fine; we only worry about hangs
                    }
                }
            }
        }
    }

    @Nested
    inner class AsDeferredTests {
        @Test
        fun `asDeferred FAST completed CF`() =
            runBlocking {
                val cf = java.util.concurrent.CompletableFuture.completedFuture(123)
                val d = cf.asDeferred()
                assertEquals(123, d.await())
            }

        @Test
        fun `asDeferred SLOW later completion`() =
            runBlocking {
                val cf = java.util.concurrent.CompletableFuture<Int>()
                val d = cf.asDeferred()
                cf.complete(7)
                assertEquals(7, d.await())
            }

        @Test
        fun `asDeferred propagates failure`() =
            runBlocking {
                val cf = java.util.concurrent.CompletableFuture<Int>()
                val d = cf.asDeferred()
                cf.completeExceptionally(IllegalStateException("x"))
                val ex = assertThrows<IllegalStateException> { d.await() }
                assertEquals("x", ex.message)
            }

        @Test
        fun `asDeferred cancels underlying CF on cancellation`() =
            runBlocking {
                val cf = java.util.concurrent.CompletableFuture<Int>()
                val d = cf.asDeferred()
                val cancel = CancellationException("stop")
                d.cancel(cancel)
                assertTrue(cf.isCancelled)
                val ex = assertThrows<CancellationException> { d.await() }
                assertEquals("stop", ex.message)
            }
    }
}
