@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.deferred.completableDeferred
import viaduct.deferred.completedDeferred

class ValueTest {
    private class TestException : Exception()

    @Test
    fun `fromValue -- getOrThrow`() {
        assertEquals(1, Value.fromValue(1).getOrThrow())
    }

    @Test
    fun `fromValue -- map`() {
        assertEquals(
            Value.fromValue(6),
            Value.fromValue(2).map { it * 3 }
        )
    }

    @Test
    fun `fromValue -- map throws`() {
        assertThrows<TestException> {
            Value.fromValue(2)
                .map { throw TestException() }
        }
    }

    @Test
    fun `fromValue -- flatMap`() {
        assertEquals(
            Value.fromValue(6),
            Value.fromValue(2).flatMap { Value.fromValue(it * 3) }
        )
    }

    @Test
    fun `fromValue -- flatMap throws`() {
        assertThrows<TestException> {
            Value.fromValue(2).flatMap<Nothing> { throw TestException() }
        }
    }

    @Test
    fun `fromValue -- asDeferred`(): Unit =
        runBlocking {
            val v = Value.fromValue(2)
            assertEquals(2, v.asDeferred().await())
        }

    @Test
    fun `fromValue -- recover`() {
        assertEquals(
            Value.fromValue(2),
            Value.fromValue(2).recover { fail() }
        )
    }

    @Test
    fun `fromValue -- recover throws`() {
        // block is not invoked for Sync.recover
        assertEquals(
            Value.fromValue(2),
            Value.fromValue(2).recover { throw TestException() }
        )
    }

    @Test
    fun `fromValue -- thenApply`() {
        assertEquals(
            Value.fromValue(6),
            Value.fromValue(2).thenApply { r, e ->
                assertNull(e)
                assertNotNull(r)
                r!! * 3
            }
        )
    }

    @Test
    fun `fromValue -- thenApply throws`() {
        assertThrows<TestException> {
            Value.fromValue(2).thenApply { _, _ -> throw TestException() }
        }
    }

    @Test
    fun `fromValue -- thenCompose`() {
        assertEquals(
            Value.fromValue(6),
            Value.fromValue(2).thenCompose { v, e ->
                assertNull(e)
                assertNotNull(v)
                Value.fromValue(v!! * 3)
            }
        )
    }

    @Test
    fun `fromValue -- thenCompose throws`() {
        assertThrows<TestException> {
            Value.fromValue(2).thenCompose<Nothing> { _, _ -> throw TestException() }
        }
    }

    @Test
    fun `fromThrowable -- getOrThrow`() {
        assertThrows<TestException> {
            Value.fromThrowable<Unit>(TestException()).getOrThrow()
        }
    }

    @Test
    fun `fromThrowable -- map`() {
        val v = Value.fromThrowable<Int>(RuntimeException())
        val v2 = v.map { it * 3 }
        assertEquals(v, v2)
    }

    @Test
    fun `fromThrowable -- map throws`() {
        val v = Value.fromThrowable<Int>(RuntimeException())

        // block is not invoked for Throw.map
        assertEquals(v, v.map { it * 3 })
    }

    @Test
    fun `fromThrowable -- flatMap`() {
        val v = Value.fromThrowable<Int>(RuntimeException())
        val v2 = v.flatMap { Value.fromValue(it * 3) }
        assertEquals(v, v2)
    }

    @Test
    fun `fromThrowable -- flatMap throws`() {
        val v = Value.fromThrowable<Int>(RuntimeException())

        // block is not invoked for Throw.flatMap
        assertEquals(v, v.flatMap { Value.fromValue(it * 3) })
    }

    @Test
    fun `fromThrowable -- asDeferred`(): Unit =
        runBlocking {
            assertThrows<RuntimeException> {
                Value.fromThrowable<Int>(RuntimeException())
                    .asDeferred()
                    .await()
            }
        }

    @Test
    fun `fromThrowable -- recover`() {
        assertEquals(
            Value.fromValue(2),
            Value.fromThrowable<Int>(RuntimeException())
                .recover { Value.fromValue(2) }
        )
    }

    @Test
    fun `fromThrowable -- recover throws`() {
        assertThrows<TestException> {
            Value.fromThrowable<Int>(RuntimeException()).recover { throw TestException() }
        }
    }

    @Test
    fun `fromThrowable -- thenApply`() {
        val v = Value.fromThrowable<Int>(RuntimeException())
            .thenApply { v, e ->
                assertNull(v)
                assertTrue(e is RuntimeException)
                3
            }
        assertEquals(Value.fromValue(3), v)
    }

    @Test
    fun `fromThrowable -- thenApply throws`() {
        assertThrows<TestException> {
            Value.fromThrowable<Int>(RuntimeException())
                .thenApply { _, _ -> throw TestException() }
        }
    }

    @Test
    fun `fromThrowable -- thenCompose`() {
        val v = Value.fromThrowable<Int>(RuntimeException())
            .thenCompose { v, e ->
                assertNull(v)
                assertTrue(e is RuntimeException)
                Value.fromValue(3)
            }
        assertEquals(Value.fromValue(3), v)
    }

    @Test
    fun `fromThrowable -- thenCompose throws`() {
        assertThrows<TestException> {
            Value.fromThrowable<Int>(RuntimeException())
                .thenCompose<Nothing> { _, _ -> throw TestException() }
        }
    }

    @Test
    fun `fromDeferred -- completed deferred`() {
        assertEquals(
            Value.fromValue(1),
            Value.fromDeferred(completedDeferred(1))
        )
    }

    @Test
    fun `fromDeferred -- cancelled completed deferred stays cancelled`() {
        val cancel = CancellationException("stop")
        val def = CompletableDeferred<Int>().apply { cancel(cancel) }

        val value = Value.fromDeferred(def)

        val deferred = value.asDeferred()
        assertTrue(deferred.isCancelled)
        val thrown = assertThrows<CancellationException> { runBlocking { deferred.await() } }
        assertEquals("stop", thrown.message)
    }

    @Test
    fun `fromDeferred -- pending deferred`() {
        val def = CompletableDeferred<Int>()
        val v = Value.fromDeferred(def)
        assertTrue(v !is Value.Sync)
        assertSame(def, v.asDeferred())
    }

    @Test
    fun `fromDeferred -- map pending deferred`(): Unit =
        runBlocking {
            val def = CompletableDeferred<Int>()
            val v = Value.fromDeferred(def)
                .map { it * 3 }
            def.complete(2)
            assertEquals(6, v.asDeferred().await())
        }

    @Test
    fun `fromDeferred -- map pending deferred throws`(): Unit =
        runBlocking {
            val def = CompletableDeferred<Int>()
            val v = Value.fromDeferred(def)
                .map { throw TestException() }
            def.complete(2)
            assertThrows<TestException> {
                v.asDeferred().await()
            }
        }

    @Test
    fun `fromDeferred -- map completed deferred`() {
        val def = CompletableDeferred<Int>()
        val v = Value.fromDeferred(def).let { v ->
            def.complete(2)
            v.map { it * 3 }
        }
        // mapping a completed deferred should return a Value.fromValue
        assertEquals(Value.fromValue(6), v)
    }

    @Test
    fun `fromDeferred -- map completed deferred throws`(): Unit =
        runBlocking {
            val def = CompletableDeferred<Int>()
            val v = Value.fromDeferred(def).let { v ->
                def.complete(2)
                v.map { throw TestException() }
            }
            assertThrows<TestException> {
                v.asDeferred().await()
            }
        }

    @Test
    fun `fromDeferred -- flatMap pending deferred`(): Unit =
        runBlocking {
            val def = CompletableDeferred<Int>()
            val v = Value.fromDeferred(def)
                .map { Value.fromValue(it * 3) }
            def.complete(2)
            assertEquals(Value.fromValue(6), v.asDeferred().await())
        }

    @Test
    fun `fromDeferred -- flatMap pending deferred throws`(): Unit =
        runBlocking {
            val def = CompletableDeferred<Int>()
            val v = Value.fromDeferred(def)
                .flatMap<Nothing> { throw TestException() }
            def.complete(2)
            assertThrows<TestException> {
                v.asDeferred().await()
            }
        }

    @Test
    fun `fromDeferred -- flatMap completed deferred`(): Unit =
        runBlocking {
            val def = CompletableDeferred<Int>()
            val v = Value.fromDeferred(def).let { v ->
                def.complete(2)
                // flatMapping a completed deferred should return a Value.fromValue
                v.flatMap { Value.fromValue(it * 3) }
            }
            assertEquals(Value.fromValue(6), v)
        }

    @Test
    fun `fromDeferred -- asDeferred`() {
        val def = CompletableDeferred<Int>()
        assertSame(
            def,
            Value.fromDeferred(def).asDeferred()
        )
    }

    @Test
    fun `fromDeferred -- recover pending deferred`() {
        runBlocking {
            val def = CompletableDeferred<Int>()
            val v = Value.fromDeferred(def)
                .recover { e ->
                    assertTrue(e is TestException)
                    Value.fromValue(3)
                }
            def.completeExceptionally(TestException())
            assertEquals(3, v.asDeferred().await())
        }
    }

    @Test
    fun `fromDeferred -- recover pending deferred throws`(): Unit =
        runBlocking {
            val def = CompletableDeferred<Int>()
            val v = Value.fromDeferred(def)
                .recover { throw TestException() }
            def.complete(2)
            assertEquals(2, v.asDeferred().await())
        }

    @Test
    fun `fromDeferred -- recover completed deferred`() {
        runBlocking {
            val def = CompletableDeferred<Int>()
            val v = Value.fromDeferred(def).let { v ->
                def.completeExceptionally(TestException())
                // recover'ing a completed deferred should return a Value.fromValue
                v.recover { e ->
                    assertTrue(e is TestException)
                    Value.fromValue(3)
                }
            }
            assertEquals(Value.fromValue(3), v)
        }
    }

    @Test
    fun `fromDeferred -- thenApply pending deferred`() {
        runBlocking {
            val def = CompletableDeferred<Int>()
            val value = Value.fromDeferred(def).let {
                it.thenApply { v, e ->
                    assertNotNull(v)
                    assertNull(e)
                    v!! * 3
                }
            }
            def.complete(2)
            assertEquals(6, value.asDeferred().await())
        }
    }

    @Test
    fun `fromDeferred -- thenApply exceptional pending deferred`() {
        runBlocking {
            val def = CompletableDeferred<Int>()
            val value = Value.fromDeferred(def).let {
                it.thenApply { v, e ->
                    assertNull(v)
                    assertTrue(e is TestException)
                    3
                }
            }
            def.completeExceptionally(TestException())
            assertEquals(3, value.asDeferred().await())
        }
    }

    @Test
    fun `fromDeferred -- thenApply completed deferred`() {
        val def = CompletableDeferred<Int>()
        val value = Value.fromDeferred(def).let {
            def.complete(2)
            it.thenApply { v, e ->
                assertNotNull(v)
                assertNull(e)
                v!! * 3
            }
        }
        // thenApply'ing a completed deferred should return a Value.fromValue
        assertEquals(
            Value.fromValue(6),
            value
        )
    }

    @Test
    fun `fromDeferred -- thenApply exceptional completed deferred`() {
        val def = CompletableDeferred<Int>()
        val value = Value.fromDeferred(def).let {
            def.completeExceptionally(TestException())
            it.thenApply { v, e ->
                assertNull(v)
                assertTrue(e is TestException)
                3
            }
        }
        // thenApply'ing a completed deferred should return a Value.fromValue
        assertEquals(
            Value.fromValue(3),
            value
        )
    }

    @Test
    fun `fromDeferred -- thenCompose pending deferred`() {
        runBlocking {
            val def = CompletableDeferred<Int>()
            val value = Value.fromDeferred(def)
                .thenCompose { v, e ->
                    assertNotNull(v)
                    assertNull(e)
                    Value.fromValue(v!! * 3)
                }
            def.complete(2)
            assertEquals(6, value.asDeferred().await())
        }
    }

    @Test
    fun `fromDeferred -- thenCompose exceptional pending deferred`() {
        runBlocking {
            val def = CompletableDeferred<Int>()
            val value = Value.fromDeferred(def)
                .thenCompose { v, e ->
                    assertNull(v)
                    assertTrue(e is TestException)
                    Value.fromValue(3)
                }
            def.completeExceptionally(TestException())
            assertEquals(3, value.asDeferred().await())
        }
    }

    @Test
    fun `fromDeferred -- thenCompose completed deferred`() {
        val def = CompletableDeferred<Int>()
        val value = Value.fromDeferred(def).let {
            def.complete(2)
            it.thenCompose { v, e ->
                assertNotNull(v)
                assertNull(e)
                Value.fromValue(v!! * 3)
            }
        }
        // thenCompose'ing a completed deferred should return a Value.fromValue
        assertEquals(
            Value.fromValue(6),
            value
        )
    }

    @Test
    fun `fromDeferred -- thenCompose exceptional completed deferred`() {
        val def = CompletableDeferred<Int>()
        val value = Value.fromDeferred(def).let {
            def.completeExceptionally(TestException())
            it.thenCompose { v, e ->
                assertNull(v)
                assertTrue(e is TestException)
                Value.fromValue(3)
            }
        }
        // thenCompose'ing a completed deferred should return a Value.fromValue
        assertEquals(
            Value.fromValue(3),
            value
        )
    }

    @Test
    fun `fromResult -- successful result`() {
        assertEquals(
            Value.fromValue(1),
            Value.fromResult(runCatching { 1 })
        )
    }

    @Test
    fun `fromResult -- failed result`() {
        val err = TestException()
        assertEquals(
            Value.fromThrowable<Int>(err),
            Value.fromResult(runCatching { throw err })
        )
    }

    @Test
    fun `waitAll -- empty`() {
        assertEquals(
            Value.fromValue(Unit),
            Value.waitAll(emptyList<Value<Int>>())
        )
    }

    @Test
    fun `waitAll -- sync values`() {
        val values = listOf(
            Value.fromValue(1),
            Value.fromValue(2)
        )
        assertEquals(
            Value.fromValue(Unit),
            Value.waitAll(values)
        )
    }

    @Test
    fun `waitAll -- throwable values`() {
        val err = RuntimeException()
        val values = listOf(
            Value.fromValue(1),
            Value.fromThrowable(err),
            Value.fromValue(2),
        )
        assertEquals(
            Value.fromThrowable<Unit>(err),
            Value.waitAll(values)
        )
    }

    @Test
    fun `waitAll -- async values`() {
        val def1 = completableDeferred<Int>()
        val def2 = completableDeferred<Int>()
        val result = Value.waitAll(
            listOf(
                Value.fromDeferred(def1),
                Value.fromDeferred(def2)
            )
        )

        // result is not ready yet
        assertThrows<Exception> { result.getCompleted() }

        def1.complete(1)
        // result is still not ready yet
        assertThrows<Exception> { result.getCompleted() }

        def2.complete(2)
        // result is ready
        assertSame(Unit, result.getCompleted())
    }
}

private fun <T> assertNotNull(
    t: T?,
    msg: String? = null
): T = t ?: fail<Nothing>(msg)
