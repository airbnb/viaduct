@file:Suppress("ForbiddenImport")

package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import model.testing.TestWorld

class PromiseTest {
    @Test
    fun `completed promise returns its value and reports losing completion`() =
        runBlocking {
            val promise = Promise.of("ready")

            assertTrue(promise.isCompleted)
            assertEquals("ready", promise.get())
            assertEquals("ready", promise.await())
            assertFalse(promise.complete("again"))
            assertFalse(promise.cancel(CancellationException("late")))
        }

    @Test
    fun `deferred promise throws from get and resumes await after completion`() =
        runBlocking {
            val promise = Promise.ofDeferred<String>()
            val awaited = async { promise.await() }

            assertFalse(promise.isCompleted)
            assertFailsWith<UncompletedPromiseException> {
                promise.get()
            }
            assertFalse(awaited.isCompleted)

            assertTrue(promise.complete("ready"))

            assertTrue(promise.isCompleted)
            assertEquals("ready", awaited.await())
            assertEquals("ready", promise.get())
            assertFalse(promise.complete("again"))
        }

    @Test
    fun `cancelled promise throws its cause from get and await`() =
        runBlocking {
            val promise = Promise.ofDeferred<String>()
            val cancellation = CancellationException("cancelled")

            assertTrue(promise.cancel(cancellation))

            assertFailsWith<CancellationException> { promise.get() }
            assertFailsWith<CancellationException> { promise.await() }
            assertFalse(promise.complete("late"))
            assertFalse(promise.cancel(cancellation))
        }

    @Test
    fun `failed promise throws its cause from get and await`() =
        runBlocking {
            val promise = Promise.ofDeferred<String>()
            val failure = IllegalStateException("failed")

            assertTrue(promise.fail(failure))

            assertEquals(failure, assertFailsWith<IllegalStateException> { promise.get() })
            assertEquals(failure, assertFailsWith<IllegalStateException> { promise.await() })
            assertFalse(promise.complete("late"))
            assertFalse(promise.fail(IllegalStateException("later")))
        }

    @Test
    fun `cancel atomically admits one concurrent caller`() =
        runBlocking {
            val promise = Promise.ofDeferred<String>()
            val start = CompletableDeferred<Unit>()
            val attempts =
                List(64) { index ->
                    async(Dispatchers.Default) {
                        start.await()
                        promise.cancel(CancellationException("cancellation-$index"))
                    }
                }

            start.complete(Unit)
            val results = attempts.awaitAll()

            assertEquals(1, results.count { it })
            assertTrue(promise.isCompleted)
            assertFailsWith<CancellationException> { promise.get() }
            assertFalse(promise.cancel(CancellationException("late")))
        }

    @Test
    fun `complete atomically admits one concurrent caller`() =
        runBlocking {
            val promise = Promise.ofDeferred<String>()
            val start = CompletableDeferred<Unit>()
            val attempts =
                List(64) { index ->
                    async(Dispatchers.Default) {
                        start.await()
                        promise.complete("value-$index")
                    }
                }

            start.complete(Unit)
            val results = attempts.awaitAll()
            val winner = results.indexOf(true)

            assertTrue(winner >= 0)
            assertEquals(1, results.count { it })
            assertEquals("value-$winner", promise.get())
            assertFalse(promise.complete("late"))
        }

    @Test
    fun `field promise validates before completion`() {
        val schema =
            TestWorld
                .fromSDL(
                    """
                    type Query { required: String! }
                    """.trimIndent(),
                ).schema
        val field =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Query", "required"),
                emptyMap(),
            )
        val promise =
            ObjectEngineResult
                .of(schema.requireQueryTypeDef(), mutable = true)
                .reserveCell(field)
                .also { cell -> cell.setActivated(true) }
                .value.claim()

        assertFailsWith<IllegalArgumentException> {
            promise.complete(null)
        }
        assertFailsWith<UncompletedPromiseException> {
            promise.get()
        }

        assertTrue(promise.complete("ready"))
        assertEquals("ready", promise.get())
        assertFalse(promise.complete("late"))
        assertFailsWith<IllegalArgumentException> { promise.complete(null) }
    }
}
