package viaduct.engine.runtime2.model

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnceStoreTest {
    @Test
    fun `distinguishes an absent key from a stored null`() {
        val store = OnceStore<String, Int?>()

        assertFailsWith<IllegalStateException> {
            store.read("key")
        }

        store.write("key", null)

        assertTrue(store.isSet("key"))
        assertNull(store.read("key"))
    }

    @Test
    fun `a key can be written only once`() {
        val store = OnceStore<String, Int>()

        store.write("key", 1)

        assertFailsWith<IllegalStateException> {
            store.write("key", 2)
        }
        assertEquals(1, store.read("key"))
    }

    @Test
    fun `initial values and snapshots are independent of later writes`() {
        val store = OnceStore<String, Int?>(mapOf("initial" to null))
        val snapshot = store.snapshot()

        store.write("later", 2)

        assertTrue(store.isSet("initial"))
        assertNull(store.read("initial"))
        assertEquals(mapOf("initial" to null), snapshot)
        assertFalse("later" in snapshot)
        assertEquals(mapOf("initial" to null, "later" to 2), store.snapshot())
    }

    @Test
    fun `concurrent writers produce one winner and one exception`() {
        val store = OnceStore<String, Int>()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val writers =
            listOf(1, 2).map { value ->
                thread {
                    ready.countDown()
                    start.await()
                    try {
                        store.write("key", value)
                        successes.incrementAndGet()
                    } catch (throwable: Throwable) {
                        failures.add(throwable)
                    }
                }
            }

        ready.await()
        start.countDown()
        writers.forEach(Thread::join)

        assertEquals(1, successes.get())
        assertIs<IllegalStateException>(failures.single())
        assertTrue(store.read("key") in setOf(1, 2))
    }

    @Test
    fun `concurrent writes to distinct keys are both retained`() {
        val store = OnceStore<String, Int>()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val writers =
            listOf("first" to 1, "second" to 2).map { (key, value) ->
                thread {
                    ready.countDown()
                    start.await()
                    store.write(key, value)
                }
            }

        ready.await()
        start.countDown()
        writers.forEach(Thread::join)

        assertEquals(1, store.read("first"))
        assertEquals(2, store.read("second"))
    }
}
