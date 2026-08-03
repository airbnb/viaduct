package viaduct.engine.runtime

import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData

class ResolveOnceTest {
    @Test
    fun `resolve runs block only once`() =
        runTest {
            val resolveOnce = ResolveOnce<EngineObjectData>()
            val first = mockk<EngineObjectData>()
            val second = mockk<EngineObjectData>()

            val result1 = resolveOnce.resolve { first }
            val result2 = resolveOnce.resolve { second }

            assertSame(first, result1)
            assertSame(first, result2)
        }

    @Test
    fun `await suspends until resolution completes`() =
        runTest {
            val resolveOnce = ResolveOnce<EngineObjectData>()
            val expected = mockk<EngineObjectData>()

            val deferred = async { resolveOnce.await() }
            resolveOnce.resolve { expected }

            assertSame(expected, deferred.await())
        }

    @Test
    fun `exception propagates to await calls`() =
        runTest {
            val resolveOnce = ResolveOnce<EngineObjectData>()

            assertThrows<IllegalStateException> {
                resolveOnce.resolve { throw IllegalStateException("boom") }
            }

            assertThrows<IllegalStateException> {
                resolveOnce.resolve { mockk() }
            }

            assertThrows<IllegalStateException> {
                resolveOnce.await()
            }
        }

    @Test
    fun `cancellation propagates to await calls`() =
        runTest {
            val resolveOnce = ResolveOnce<EngineObjectData>()
            val cancellation = CancellationException("resolution cancelled")

            val resolution = async {
                resolveOnce.resolve {
                    currentCoroutineContext().cancel(cancellation)
                    currentCoroutineContext().ensureActive()
                    error("unreachable")
                }
            }
            assertThrows<CancellationException> {
                resolution.await()
            }

            val propagated = assertThrows<CancellationException> {
                withTimeout(100) {
                    resolveOnce.await()
                }
            }
            assertEquals(cancellation.message, propagated.message)
        }

    @Test
    fun `resolve and await return null when block returns null`() =
        runTest {
            val resolveOnce = ResolveOnce<EngineObjectData?>()
            assertNull(resolveOnce.resolve { null })
            assertNull(resolveOnce.await())
        }

    @Test
    fun `concurrent resolves return same result`() =
        runTest {
            val resolveOnce = ResolveOnce<EngineObjectData>()
            val expected = mockk<EngineObjectData>()
            var callCount = 0

            val results = (1..10).map {
                async {
                    resolveOnce.resolve {
                        callCount++
                        expected
                    }
                }
            }.map { it.await() }

            assertEquals(1, callCount)
            results.forEach { assertSame(expected, it) }
        }
}
