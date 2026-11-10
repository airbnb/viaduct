@file:OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)

package viaduct.engine.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.CompletionHandlerException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import viaduct.deferred.RequestParentJobContextElement
import viaduct.deferred.ThreadLocalCoroutineContextManager
import viaduct.deferred.completableDeferred
import viaduct.deferred.currentRequestParentJobOrNull
import viaduct.deferred.handle
import viaduct.deferred.thenCompose
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.engine.runtime.execution.RequestScope

@Timeout(30)
class ValueThenComposeTest {
    private val failure = IllegalStateException("failure")
    private val cancellation = CancellationException("cancel")
    private val wrappedFailure = CompletionException(failure)
    private val wrappedCancellation = CompletionException(cancellation)
    private val sourceOutcomes: List<Pair<String, (CompletableDeferred<Int?>) -> Unit>> = listOf(
        "value" to { it.complete(1) },
        "null" to { it.complete(null) },
        "failure" to { it.completeExceptionally(failure) },
        "cancellation" to { it.cancel(cancellation) },
        "wrapped failure" to { it.completeExceptionally(wrappedFailure) },
        "wrapped cancellation" to { it.completeExceptionally(wrappedCancellation) },
    )
    private val blockOutcomes: List<Pair<String, () -> Pair<Value<Int?>, () -> Unit>>> = listOf(
        "sync value" to { Value.fromValue<Int?>(2) to {} },
        "sync null" to { Value.fromValue<Int?>(null) to {} },
        "sync failure" to { Value.fromThrowable<Int?>(failure) to {} },
        "sync cancellation" to { Value.fromThrowable<Int?>(cancellation) to {} },
        "sync wrapped failure" to { Value.fromThrowable<Int?>(wrappedFailure) to {} },
        "sync wrapped cancellation" to { Value.fromThrowable<Int?>(wrappedCancellation) to {} },
        "throws failure" to { throw failure },
        "throws cancellation" to { throw cancellation },
        "throws wrapped failure" to { throw wrappedFailure },
        "throws wrapped cancellation" to { throw wrappedCancellation },
        "async value" to { pending { it.complete(3) } },
        "async failure" to { pending { it.completeExceptionally(failure) } },
        "async cancellation" to { pending { it.cancel(cancellation) } },
        "async wrapped failure" to { pending { it.completeExceptionally(wrappedFailure) } },
        "async wrapped cancellation" to { pending { it.completeExceptionally(wrappedCancellation) } },
        "completed async value" to {
            val deferred = CompletableDeferred<Int?>()
            val value = Value.fromDeferred(deferred)
            deferred.complete(4)
            value to {}
        },
    )

    private fun pending(finish: (CompletableDeferred<Int?>) -> Unit): Pair<Value<Int?>, () -> Unit> {
        val deferred = CompletableDeferred<Int?>()
        return Value.fromDeferred(deferred) to { finish(deferred) }
    }

    private fun <T, U> compose(
        reference: Boolean,
        source: Deferred<T>,
        value: Value<T> = Value.fromDeferred(source),
        block: (T?, Throwable?) -> Value<U>,
    ): Value<U> =
        if (reference) {
            Value.fromDeferred(source.handle(block).thenCompose { it.asDeferred() })
        } else {
            value.thenCompose(block)
        }

    private data class Outcome(
        val sync: Boolean,
        val pendingBeforeInnerCompletion: Boolean,
        val cancelled: Boolean,
        val result: Int?,
        val failure: Throwable?,
        val calls: List<Pair<Int?, Throwable?>>,
    )

    private fun scenario(
        reference: Boolean,
        preCompleted: Boolean,
        finishSource: (CompletableDeferred<Int?>) -> Unit,
        next: () -> Pair<Value<Int?>, () -> Unit>,
    ): Outcome {
        val source = CompletableDeferred<Int?>()
        val input = Value.fromDeferred(source)
        val calls = mutableListOf<Pair<Int?, Throwable?>>()
        var finishInner: () -> Unit = {}
        if (preCompleted) finishSource(source)
        val output = compose(reference, source, input) { value, error ->
            calls.add(value to error)
            val (inner, finish) = next()
            finishInner = finish
            inner
        }
        if (!preCompleted) finishSource(source)
        val deferred = output.asDeferred()
        val pending = !deferred.isCompleted
        finishInner()
        val error = deferred.getCompletionExceptionOrNull()
        return Outcome(output is Value.Sync, pending, deferred.isCancelled, if (error == null) deferred.getCompleted() else null, error, calls)
    }

    @Test
    fun `matches the original composition across source timing and block outcomes`() {
        for (preCompleted in listOf(false, true)) {
            for ((sourceName, source) in sourceOutcomes) {
                for ((blockName, block) in blockOutcomes) {
                    val description = "preCompleted=$preCompleted source=$sourceName block=$blockName"
                    assertEquals(scenario(true, preCompleted, source, block), scenario(false, preCompleted, source, block), description)
                }
            }
        }
    }

    @Test
    fun `independent source cancellation reaches recovery and leaves request siblings active`() =
        runExecutionTest {
            RequestScope {
                val request = checkNotNull(currentRequestParentJobOrNull())
                for (reference in listOf(true, false)) {
                    for (preCompleted in listOf(false, true)) {
                        val source = CompletableDeferred<Int>()
                        val input = Value.fromDeferred(source)
                        val sibling = completableDeferred<Int>()
                        val calls = mutableListOf<Pair<Int?, Throwable?>>()
                        if (preCompleted) source.cancel(cancellation)
                        val output = compose(reference, source, input) { value, error ->
                            calls += value to error
                            Value.fromValue(42)
                        }
                        if (!preCompleted) source.cancel(cancellation)
                        assertEquals(listOf(null to cancellation), calls)
                        assertEquals(42, output.getCompleted())
                        assertTrue(request.isActive)
                        assertTrue(sibling.isActive)
                        sibling.complete(1)
                    }
                }
            }
        }

    @Test
    fun `cancelled nested caller skips source cancellation completed on another thread`() =
        runExecutionTest {
            RequestScope {
                val request = checkNotNull(currentRequestParentJobOrNull())
                for (reference in listOf(true, false)) {
                    for (preCompleted in listOf(false, true)) {
                        val source = CompletableDeferred<Int>()
                        val input = Value.fromDeferred(source)
                        val caller = Job()
                        val calls = AtomicInteger()
                        lateinit var output: Deferred<Int>
                        try {
                            if (preCompleted) source.cancel(cancellation)
                            CoroutineScope(coroutineContext + caller).launch(start = CoroutineStart.UNDISPATCHED) {
                                if (preCompleted) caller.cancel()
                                output = compose(reference, source, input) { _, _ ->
                                    calls.incrementAndGet()
                                    Value.fromValue(42)
                                }.asDeferred()
                                awaitCancellation()
                            }
                            caller.cancelAndJoin()
                            CompletableFuture.runAsync { source.cancel(cancellation) }.get(5, TimeUnit.SECONDS)
                            assertEquals(0, calls.get())
                            assertSame(cancellation, output.getCompletionExceptionOrNull())
                            assertTrue(request.isActive)
                        } finally {
                            caller.cancelAndJoin()
                            source.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun `composition registered after its coroutine finishes follows the default job`() =
        runExecutionTest {
            for (reference in listOf(true, false)) {
                for (defaultJobCancelled in listOf(false, true)) {
                    val defaultJob = SupervisorJob()
                    val source = CompletableDeferred<Int>()
                    val calls = AtomicInteger()
                    lateinit var output: Deferred<Int>
                    try {
                        val finished = CoroutineScope(
                            Dispatchers.Unconfined + ThreadLocalCoroutineContextManager.ContextElement(defaultJob)
                        ).launch(start = CoroutineStart.LAZY) {}
                        finished.invokeOnCompletion {
                            output = compose(reference, source) { _, _ ->
                                calls.incrementAndGet()
                                Value.fromValue(42)
                            }.asDeferred()
                        }
                        finished.start()
                        finished.join()
                        if (defaultJobCancelled) defaultJob.cancel()
                        source.cancel(cancellation)
                        assertEquals(if (defaultJobCancelled) 0 else 1, calls.get())
                        if (defaultJobCancelled) {
                            assertSame(cancellation, output.getCompletionExceptionOrNull())
                        } else {
                            assertEquals(42, output.getCompleted())
                        }
                    } finally {
                        defaultJob.cancelAndJoin()
                        source.cancel()
                    }
                }
            }
        }

    private class GatedDeferred<T>(
        private val delegate: Deferred<T>,
        private val beforeRegistration: () -> Unit,
    ) : Deferred<T> by delegate {
        override fun invokeOnCompletion(handler: CompletionHandler): DisposableHandle {
            beforeRegistration()
            return delegate.invokeOnCompletion(handler)
        }
    }

    @Test
    fun `source completion between pending check and registration invokes the callback once`() {
        val source = CompletableDeferred<Int>()
        val input = Value.fromDeferred(GatedDeferred(source) { source.complete(7) })
        var calls = 0
        val output = input.thenCompose { value, error ->
            calls++
            assertEquals(7, value)
            assertEquals(null, error)
            Value.fromValue(8)
        }
        assertEquals(8, output.getCompleted())
        assertEquals(1, calls)
    }

    private enum class Boundary { BEFORE_SOURCE, DURING_CALLBACK, BEFORE_INNER_REGISTRATION, AFTER_LINKING }

    @Test
    fun `cancellation around linking preserves ownership and releases request children`() =
        runTest {
            for (reference in listOf(true, false)) {
                for (cancelRequest in listOf(false, true)) {
                    for (boundary in Boundary.entries) {
                        val description = "reference=$reference request=$cancelRequest boundary=$boundary"
                        val parent = SupervisorJob()
                        val sibling = CompletableDeferred<Int>(parent)
                        val source = CompletableDeferred<Int>()
                        val inner = CompletableDeferred<Int>()
                        lateinit var output: Deferred<Int>

                        fun cancel() {
                            if (cancelRequest) parent.cancel(cancellation) else output.cancel(cancellation)
                        }
                        try {
                            val gatedInner = GatedDeferred(inner) {
                                if (boundary == Boundary.BEFORE_INNER_REGISTRATION) cancel()
                            }
                            var calls = 0
                            var completions = 0
                            output = withContext(RequestParentJobContextElement(parent)) {
                                compose(reference, source) { _, _ ->
                                    calls++
                                    if (boundary == Boundary.DURING_CALLBACK) cancel()
                                    Value.fromDeferred(gatedInner)
                                }.asDeferred()
                            }
                            output.invokeOnCompletion { completions++ }
                            if (boundary == Boundary.BEFORE_SOURCE) cancel()
                            source.complete(1)
                            if (boundary == Boundary.AFTER_LINKING) cancel()
                            assertSame(cancellation, output.getCompletionExceptionOrNull(), description)
                            assertFalse(source.isCancelled, description)
                            assertEquals(if (cancelRequest && boundary == Boundary.BEFORE_SOURCE) 0 else 1, calls, description)
                            val linked = boundary == Boundary.BEFORE_INNER_REGISTRATION || boundary == Boundary.AFTER_LINKING
                            assertEquals(linked, inner.isCancelled, description)
                            if (linked) assertSame(cancellation, inner.getCompletionExceptionOrNull(), description)
                            assertEquals(!cancelRequest, parent.isActive, description)
                            assertEquals(!cancelRequest, sibling.isActive, description)
                            assertEquals(if (cancelRequest) emptyList() else listOf(sibling), parent.children.toList(), description)
                            inner.complete(2)
                            assertSame(cancellation, output.getCompletionExceptionOrNull(), description)
                            assertEquals(1, completions, description)
                        } finally {
                            source.cancel()
                            inner.cancel()
                            parent.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun `callback uses the completing thread and its request context`() =
        runTest {
            val source = CompletableDeferred<Int>()
            val parent = SupervisorJob()
            val completingParent = SupervisorJob()
            val marker = ThreadLocal<String?>()
            val executor = Executors.newSingleThreadExecutor()
            try {
                var callbackThread: Thread? = null
                val output = withContext(RequestParentJobContextElement(parent)) {
                    Value.fromDeferred(source).thenCompose { value, _ ->
                        assertEquals("completion", marker.get())
                        callbackThread = Thread.currentThread()
                        assertSame(completingParent, currentRequestParentJobOrNull())
                        Value.fromValue(value!! + 1)
                    }
                }
                val completionThread = executor.submit<Thread> {
                    marker.set("completion")
                    val element = RequestParentJobContextElement(completingParent)
                    val previous = element.updateThreadContext(EmptyCoroutineContext)
                    try {
                        source.complete(1)
                        Thread.currentThread()
                    } finally {
                        element.restoreThreadContext(EmptyCoroutineContext, previous)
                        marker.remove()
                    }
                }.get(5, TimeUnit.SECONDS)
                assertSame(completionThread, callbackThread)
                assertEquals(2, output.getCompleted())
                assertFalse(parent.children.any())
            } finally {
                parent.cancel()
                completingParent.cancel()
                executor.shutdownNow()
            }
        }

    @Test
    fun `one request child detaches on success or field failure without cancelling siblings`() =
        runTest {
            for (fails in listOf(false, true)) {
                val parent = SupervisorJob()
                try {
                    withContext(RequestParentJobContextElement(parent)) {
                        val sibling = completableDeferred<Int>()
                        val source = CompletableDeferred<Int>()
                        val output = Value.fromDeferred(source).thenCompose { value, _ ->
                            if (fails) throw failure
                            Value.fromValue(value!! + 1)
                        }.asDeferred()
                        assertEquals(setOf(sibling, output), parent.children.toSet())
                        source.complete(1)
                        if (fails) assertSame(failure, output.getCompletionExceptionOrNull()) else assertEquals(2, output.getCompleted())
                        assertTrue(parent.isActive)
                        assertTrue(sibling.isActive)
                        assertEquals(listOf(sibling), parent.children.toList())
                        sibling.complete(3)
                        assertFalse(parent.children.any())
                    }
                } finally {
                    parent.cancel()
                }
            }
        }

    @Test
    fun `an already cancelled request parent does not own new composition`() =
        runTest {
            val parent = SupervisorJob().also { it.cancel(cancellation) }
            withContext(RequestParentJobContextElement(parent)) {
                val source = CompletableDeferred<Int>()
                val output = Value.fromDeferred(source).thenCompose { value, _ -> Value.fromValue(value!! + 1) }
                source.complete(1)
                assertEquals(2, output.getCompleted())
                assertFalse(parent.children.any())
            }
        }

    @Test
    fun `request cancellation discards returned and wrapped failures but direct critical throws escape`() =
        runTest {
            for (reference in listOf(true, false)) {
                for (error in listOf(failure, cancellation, Error("fatal"), CompletionHandlerException("handler", failure))) {
                    for (delivery in listOf("returned", "wrapped throw", "direct throw")) {
                        val parent = SupervisorJob()
                        val source = CompletableDeferred<Int>()
                        try {
                            val output = withContext(RequestParentJobContextElement(parent)) {
                                compose<Int, Int>(reference, source) { _, _ ->
                                    parent.cancel(cancellation)
                                    when (delivery) {
                                        "direct throw" -> throw error
                                        "wrapped throw" -> throw CompletionException(error)
                                        else -> Value.fromThrowable(error)
                                    }
                                }.asDeferred()
                            }
                            if (delivery == "direct throw" && (error is Error || error is CompletionHandlerException)) {
                                val thrown = assertThrows<CompletionHandlerException> { source.complete(1) }
                                assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === error })
                            } else {
                                source.complete(1)
                            }
                            assertSame(cancellation, output.getCompletionExceptionOrNull())
                            assertFalse(parent.children.any())
                        } finally {
                            parent.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun `critical callback failures escape the completing thread`() {
        for (reference in listOf(true, false)) {
            for (error in listOf(Error("fatal"), CompletionHandlerException("handler", failure))) {
                for (returnsFailure in listOf(false, true)) {
                    val source = CompletableDeferred<Int>()
                    val output = compose<Int, Int>(reference, source) { _, _ ->
                        if (returnsFailure) Value.fromThrowable(error) else throw error
                    }.asDeferred()
                    val thrown = assertThrows<CompletionHandlerException> { source.complete(1) }
                    assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === error })
                    assertFalse(output.isCompleted)
                    output.cancel()
                }
            }
        }
    }
}
