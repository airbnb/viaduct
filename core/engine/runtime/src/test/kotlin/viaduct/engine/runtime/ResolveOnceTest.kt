package viaduct.engine.runtime

import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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
    fun `await suspends until resolve completes`() =
        runTest {
            val resolveOnce = ResolveOnce<EngineObjectData>()
            val expected = mockk<EngineObjectData>()

            val deferred = async { resolveOnce.await() }
            resolveOnce.resolve { expected }

            assertSame(expected, deferred.await())
        }

    @Test
    fun `exception propagates to subsequent resolve and await calls`() =
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
