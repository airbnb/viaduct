package viaduct.engine.runtime2.resolution.framework

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.model.Promise

class CheckerPublicationTest {
    @Test
    fun `absence and success allow effects while denial suppresses them`(): Unit =
        runTest {
            for ((result, allowed) in listOf(null to true, CheckerResult.Success to true, Denial to false)) {
                val destination = Promise.ofDeferred<CheckerResult?>()

                assertEquals(allowed, publishCheckerResult(destination, onFailure = { error("Unexpected failure") }) { result })
                assertSame(result, destination.get())
            }
        }

    @Test
    fun `ordinary and callback cancellation failures complete bindings before failing the checker`(): Unit =
        runTest {
            for (cause in listOf(IllegalStateException("checker failed"), CancellationException("callback-local cancellation"))) {
                val destination = Promise.ofDeferred<CheckerResult?>()
                val binding = Promise.ofDeferred<Unit>()

                val allowed = publishCheckerResult(destination, onFailure = {
                    assertFalse(destination.isCompleted)
                    binding.complete(Unit)
                }) { throw cause }

                assertFalse(allowed)
                assertTrue(currentCoroutineContext().isActive)
                assertTrue(binding.isCompleted)
                assertSame(cause, assertThrows(cause.javaClass) { destination.get() })
            }
        }

    @Test
    fun `job cancellation leaves checker and binding cancellation to their owner`(): Unit =
        runTest {
            withTimeout(2_000) {
                val destination = Promise.ofDeferred<CheckerResult?>()
                val binding = Promise.ofDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val job = launch {
                    publishCheckerResult(destination, onFailure = { binding.complete(Unit) }) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                entered.await()
                job.cancelAndJoin()

                assertTrue(job.isCancelled)
                assertFalse(destination.isCompleted)
                assertFalse(binding.isCompleted)
            }
        }

    @Test
    fun `interruption and JVM errors escape without checker failure cleanup`() {
        val causes = listOf(InterruptedException("interrupted"), OutOfMemoryError("JVM signal"), AssertionError("engine defect"))
        for (cause in causes) {
            val destination = Promise.ofDeferred<CheckerResult?>()
            var cleanedUp = false

            val thrown = assertThrows(cause.javaClass) {
                runTest {
                    publishCheckerResult(destination, onFailure = { cleanedUp = true }) { throw cause }
                }
            }

            assertSame(cause, thrown)
            assertFalse(cleanedUp)
            assertFalse(destination.isCompleted)
        }
    }

    @Test
    fun `failure cleanup defects escape without publishing a checker outcome`() {
        val destination = Promise.ofDeferred<CheckerResult?>()
        val cleanupFailure = IllegalStateException("binding already closed")

        val thrown = assertThrows<IllegalStateException> {
            runTest {
                publishCheckerResult(destination, onFailure = { throw cleanupFailure }) { error("checker failed") }
            }
        }

        assertSame(cleanupFailure, thrown)
        assertFalse(destination.isCompleted)
    }

    @Test
    fun `duplicate success is an invariant failure without running failure cleanup`() {
        val destination = Promise.of<CheckerResult?>(CheckerResult.Success)
        var cleanedUp = false

        val thrown = assertThrows<IllegalStateException> {
            runTest {
                publishCheckerResult(destination, onFailure = { cleanedUp = true }) { null }
            }
        }

        assertEquals("Checker result was completed twice", thrown.message)
        assertFalse(cleanedUp)
        assertSame(CheckerResult.Success, destination.get())
    }

    @Test
    fun `duplicate failure preserves the original checker outcome`() {
        val destination = Promise.of<CheckerResult?>(CheckerResult.Success)

        val thrown = assertThrows<IllegalStateException> {
            runTest {
                publishCheckerResult(destination) { error("checker failed") }
            }
        }

        assertEquals("Checker failure was published twice", thrown.message)
        assertSame(CheckerResult.Success, destination.get())
    }

    private object Denial : CheckerResult.Error {
        override val error = SecurityException("denied")

        override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = false

        override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
    }
}
