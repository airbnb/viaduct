package viaduct.engine.runtime.execution

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletionHandlerException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import viaduct.deferred.ThreadLocalCoroutineContextManager
import viaduct.deferred.completableDeferred
import viaduct.deferred.currentRequestParentJobOrNull
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
@Timeout(15)
class ValueOnCompletionTest {
    enum class Observer { DISCARDED_THEN_APPLY, ON_COMPLETION }

    enum class Timing { SYNC, BEFORE_REGISTRATION, AFTER_REGISTRATION }

    enum class Outcome { VALUE, NULL, FAILURE, CANCELLATION }

    enum class CallbackFailure { EXCEPTION, CANCELLATION, COMPLETION_HANDLER_EXCEPTION, ERROR }

    private fun Value<Int?>.observe(
        observer: Observer,
        block: (Int?, Throwable?) -> Unit
    ) {
        when (observer) {
            Observer.DISCARDED_THEN_APPLY -> thenApply(block)
            Observer.ON_COMPLETION -> onCompletion(block)
        }
    }

    private fun fixture(
        timing: Timing,
        outcome: Outcome,
        failure: Throwable,
        cancellation: CancellationException
    ): Pair<Value<Int?>, () -> Unit> {
        val source = CompletableDeferred<Int?>()
        val finish = {
            when (outcome) {
                Outcome.VALUE -> source.complete(3)
                Outcome.NULL -> source.complete(null)
                Outcome.FAILURE -> source.completeExceptionally(failure)
                Outcome.CANCELLATION -> source.cancel(cancellation)
            }
            Unit
        }
        val value: Value<Int?> = if (timing == Timing.SYNC) {
            when (outcome) {
                Outcome.VALUE -> Value.fromValue(3)
                Outcome.NULL -> Value.fromValue(null)
                Outcome.FAILURE -> Value.fromThrowable(failure)
                Outcome.CANCELLATION -> Value.fromThrowable(cancellation)
            }
        } else {
            Value.fromDeferred(source)
        }
        if (timing == Timing.BEFORE_REGISTRATION) finish()
        return value to { if (timing == Timing.AFTER_REGISTRATION) finish() }
    }

    @ParameterizedTest
    @MethodSource("completionCases")
    fun `completion matches discarded thenApply`(
        timing: Timing,
        outcome: Outcome
    ) {
        val failure = IllegalStateException("source failed")
        val cancellation = CancellationException("source cancelled")
        val expected = when {
            outcome == Outcome.CANCELLATION -> listOf(null to cancellation)
            outcome == Outcome.FAILURE -> listOf(null to failure)
            outcome == Outcome.NULL -> listOf(null to null)
            else -> listOf(3 to null)
        }
        Observer.entries.forEach { observer ->
            val calls = mutableListOf<Pair<Int?, Throwable?>>()
            val (value, finish) = fixture(timing, outcome, failure, cancellation)
            value.observe(observer) { result, error -> calls += result to error }
            if (timing == Timing.AFTER_REGISTRATION) assertTrue(calls.isEmpty())
            finish()
            assertEquals(expected, calls, "$observer / $timing / $outcome")
        }
    }

    @ParameterizedTest
    @MethodSource("callbackFailureCases")
    fun `callback failures preserve synchronous and asynchronous policy`(
        timing: Timing,
        callbackFailure: CallbackFailure
    ) {
        val failure = when (callbackFailure) {
            CallbackFailure.EXCEPTION -> IllegalStateException("callback failed")
            CallbackFailure.CANCELLATION -> CancellationException("callback cancelled")
            CallbackFailure.COMPLETION_HANDLER_EXCEPTION -> CompletionHandlerException("critical", AssertionError("cause"))
            CallbackFailure.ERROR -> AssertionError("callback error")
        }
        Observer.entries.forEach { observer ->
            val (value, finish) = fixture(timing, Outcome.VALUE, failure, CancellationException())
            val calls = AtomicInteger()
            val thrown = runCatching {
                value.observe(observer) { _, _ ->
                    calls.incrementAndGet()
                    throw failure
                }
                finish()
            }.exceptionOrNull()
            assertEquals(1, calls.get())
            if (timing != Timing.SYNC && callbackFailure in setOf(CallbackFailure.EXCEPTION, CallbackFailure.CANCELLATION)) {
                assertEquals(null, thrown)
            } else if (timing == Timing.AFTER_REGISTRATION) {
                assertTrue(thrown is CompletionHandlerException)
                assertSame(failure, thrown!!.cause)
            } else {
                assertSame(failure, thrown)
            }
            assertEquals(3, value.getCompleted())
        }
    }

    @Test
    fun `observer creates no request child and callback failure leaves siblings active`(): Unit =
        runExecutionTest {
            RequestScope {
                val parent = checkNotNull(currentRequestParentJobOrNull())
                val source = completableDeferred<Int?>()
                val sibling = completableDeferred<Int?>()
                val children = parent.children.toSet()
                val calls = AtomicInteger()
                Value.fromDeferred(source).onCompletion { _, _ ->
                    calls.incrementAndGet()
                    throw IllegalStateException("callback failed")
                }
                assertEquals(children, parent.children.toSet())
                assertTrue(source.complete(3))
                assertEquals(1, calls.get())
                assertEquals(3, source.getCompleted())
                assertTrue(parent.isActive)
                assertTrue(sibling.isActive)
                sibling.complete(4)
                assertEquals(children - source - sibling, parent.children.toSet())
            }
        }

    @ParameterizedTest
    @EnumSource(Observer::class)
    fun `request cleanup cancels a parented source without calling the observer`(observer: Observer): Unit =
        runExecutionTest {
            val calls = AtomicInteger()
            lateinit var source: CompletableDeferred<Int?>
            RequestScope {
                source = completableDeferred()
                Value.fromDeferred(source).observe(observer) { _, _ -> calls.incrementAndGet() }
            }
            assertTrue(source.isCancelled)
            assertFalse(source.complete(3))
            assertEquals(0, calls.get())
        }

    @ParameterizedTest
    @EnumSource(Observer::class)
    fun `request cleanup skips the observer without cancelling an external source`(observer: Observer): Unit =
        runExecutionTest {
            val source = CompletableDeferred<Int?>()
            val calls = mutableListOf<Pair<Int?, Throwable?>>()
            RequestScope {
                RequestScope {
                    Value.fromDeferred(source).observe(observer) { result, error -> calls += result to error }
                }
                assertTrue(checkNotNull(currentRequestParentJobOrNull()).isActive)
            }
            assertTrue(source.isActive)
            source.complete(3)
            assertTrue(calls.isEmpty())
        }

    @ParameterizedTest
    @EnumSource(value = Timing::class, names = ["BEFORE_REGISTRATION", "AFTER_REGISTRATION"])
    fun `cancelled caller skips source cancellation including completion on another thread`(timing: Timing): Unit =
        runExecutionTest {
            RequestScope {
                val request = checkNotNull(currentRequestParentJobOrNull())
                Observer.entries.forEach { observer ->
                    val (value, finish) = fixture(timing, Outcome.CANCELLATION, IllegalStateException(), CancellationException())
                    val calls = AtomicInteger()
                    val caller = Job()
                    var registered = false
                    try {
                        CoroutineScope(coroutineContext + caller).launch(start = CoroutineStart.UNDISPATCHED) {
                            if (timing == Timing.BEFORE_REGISTRATION) caller.cancel()
                            value.observe(observer) { _, _ -> calls.incrementAndGet() }
                            registered = true
                            awaitCancellation()
                        }
                        assertTrue(registered)
                        caller.cancelAndJoin()
                        CompletableFuture.runAsync { finish() }.get(5, TimeUnit.SECONDS)
                        assertEquals(0, calls.get(), "$observer / $timing")
                        assertTrue(request.isActive)
                    } finally {
                        caller.cancelAndJoin()
                    }
                }
            }
        }

    @ParameterizedTest
    @EnumSource(Observer::class)
    fun `observer registered after its coroutine finishes follows the default job`(observer: Observer): Unit =
        runExecutionTest {
            for (defaultJobCancelled in listOf(false, true)) {
                val defaultJob = SupervisorJob()
                val source = CompletableDeferred<Int?>()
                val calls = AtomicInteger()
                try {
                    val finished = CoroutineScope(
                        Dispatchers.Unconfined + ThreadLocalCoroutineContextManager.ContextElement(defaultJob)
                    ).launch(start = CoroutineStart.LAZY) {}
                    finished.invokeOnCompletion {
                        Value.fromDeferred(source).observe(observer) { _, _ -> calls.incrementAndGet() }
                    }
                    finished.start()
                    finished.join()
                    if (defaultJobCancelled) defaultJob.cancel()
                    source.cancel(CancellationException("execution cancelled"))

                    assertEquals(if (defaultJobCancelled) 0 else 1, calls.get(), "$observer / defaultJobCancelled=$defaultJobCancelled")
                } finally {
                    defaultJob.cancelAndJoin()
                }
            }
        }

    @Test
    fun `registration racing with completion invokes the callback at most once`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { iteration ->
                val source = CompletableDeferred<Int?>()
                val value = Value.fromDeferred(source)
                val start = CyclicBarrier(3)
                val calls = AtomicInteger()
                val registration = executor.submit {
                    start.await(5, TimeUnit.SECONDS)
                    value.onCompletion { result, error ->
                        if (error is CancellationException) {
                            assertEquals(null, result)
                        } else {
                            assertEquals(3, result)
                            assertEquals(null, error)
                        }
                        calls.incrementAndGet()
                    }
                }
                val completion = executor.submit {
                    start.await(5, TimeUnit.SECONDS)
                    if (iteration % 2 == 0) source.complete(3) else source.cancel()
                }
                start.await(5, TimeUnit.SECONDS)
                registration.get(5, TimeUnit.SECONDS)
                completion.get(5, TimeUnit.SECONDS)
                assertEquals(1, calls.get())
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    companion object {
        @JvmStatic
        fun completionCases(): Stream<Arguments> = Timing.entries.flatMap { timing -> Outcome.entries.map { outcome -> Arguments.of(timing, outcome) } }.stream()

        @JvmStatic
        fun callbackFailureCases(): Stream<Arguments> = Timing.entries.flatMap { timing -> CallbackFailure.entries.map { failure -> Arguments.of(timing, failure) } }.stream()
    }
}
