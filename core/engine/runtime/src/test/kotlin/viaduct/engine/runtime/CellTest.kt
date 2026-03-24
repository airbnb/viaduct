@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CellTest {
    private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Test
    fun `set max slots`() {
        runBlocking {
            val cell = Cell.create(Cell.MAX_SLOTS)
            cell.computeIfAbsent { setter ->
                for (i in 0 until Cell.MAX_SLOTS) {
                    setter.set(i, Value.fromValue(i))
                }
            }
            assertEquals(0, cell.fetch(0))
            assertEquals(Cell.MAX_SLOTS - 1, cell.fetch(Cell.MAX_SLOTS - 1))
            assertThrows<IndexOutOfBoundsException> { cell.fetch(Cell.MAX_SLOTS) }
            assertThrows<IndexOutOfBoundsException> { cell.fetch(-1) }
        }
    }

    @Test
    fun `attempt to create with invalid number of slots`() {
        assertThrows<IllegalArgumentException> { Cell.create(Cell.MAX_SLOTS + 1) }
        assertThrows<IllegalArgumentException> { Cell.create(0) }
    }

    @Test
    fun `write then read`() {
        runBlocking {
            val cell = Cell.create(3)
            cell.computeIfAbsent { setter ->
                setter.set(0, Value.fromValue("a"))
                setter.set(1, Value.fromThrowable<Nothing>(IllegalStateException("foo")))
                setter.set(
                    2,
                    Value.fromDeferred(
                        testScope.async {
                            delay(100)
                            "b"
                        }
                    )
                )
            }
            assertEquals("a", cell.fetch(0))
            assertThrows<IllegalStateException> { cell.fetch(1) }
            assertEquals("b", cell.fetch(2))
        }
    }

    @Test
    fun `read then write`() {
        runBlocking {
            val cell = Cell.create(1)
            val fetch = testScope.async {
                cell.fetch(0)
            }
            val value = cell.getValue(0)

            delay(100)

            cell.computeIfAbsent { setter ->
                setter.set(0, Value.fromValue("value"))
            }
            assertEquals("value", fetch.await())
            assertEquals("value", value.await())
        }
    }

    @Test
    fun `computeIfUnclaimed throws if already claimed`() {
        runBlocking {
            val cell = Cell.create(1)
            cell.computeIfUnclaimed("should be successful") { setter ->
                setter.set(0, Value.fromValue("foo"))
            }
            assertThrows<IllegalStateException> {
                cell.computeIfUnclaimed("should fail") { setter ->
                    setter.set(0, Value.fromValue("bar"))
                }
            }
            assertEquals("foo", cell.fetch(0))
        }
    }

    @Test
    fun `compute block throws`() {
        runBlocking {
            val cell = Cell.create(2)
            assertThrows<NumberFormatException> {
                cell.computeIfAbsent { setter ->
                    setter.set(1, Value.fromValue("foo"))
                    throw NumberFormatException("foo")
                }
            }
            val e1 = assertThrows<RuntimeException> { cell.fetch(0) }
            val e2 = assertThrows<RuntimeException> { cell.fetch(1) }
            assertEquals(e1, e2)
            assertTrue(e1.cause is NumberFormatException)
        }
    }

    @Test
    fun `did not set all slots`() {
        runBlocking {
            val cell = Cell.create(3)
            val e = assertThrows<IllegalStateException> {
                cell.computeIfAbsent { setter ->
                    setter.set(0, Value.fromValue("foo"))
                    setter.set(2, Value.fromValue("bar"))
                }
            }
            assertTrue(e.message!!.contains("Set slots: 101"))
            val e1 = assertThrows<RuntimeException> { cell.fetch(0) }
            val e2 = assertThrows<RuntimeException> { cell.fetch(1) }
            val e3 = assertThrows<RuntimeException> { cell.fetch(2) }
            assertEquals(e1, e2)
            assertEquals(e2, e3)
            assertTrue(e1.cause is IllegalStateException)
        }
    }

    @Test
    fun `set slot more than once`() {
        runBlocking {
            val cell = Cell.create(1)
            val e = assertThrows<IllegalStateException> {
                cell.computeIfAbsent { setter ->
                    setter.set(0, Value.fromValue("foo"))
                    setter.set(0, Value.fromValue("bar"))
                }
            }
            assertTrue(e.message!!.contains("Slot 0 has been set more than once"))
            val e1 = assertThrows<RuntimeException> { cell.fetch(0) }
            assertTrue(e1.cause is IllegalStateException)
        }
    }

    @Test
    fun `set negative slotNo`() {
        val cell = Cell.create(1)
        val e = assertThrows<IndexOutOfBoundsException> {
            cell.computeIfAbsent { setter ->
                setter.set(-1, Value.fromValue("foo"))
            }
        }
        assertTrue(e.message!!.contains("Invalid slotNo -1"))
    }

    @Test
    fun `set too large slotNo`() {
        val cell = Cell.create(1)
        val e = assertThrows<IndexOutOfBoundsException> {
            cell.computeIfAbsent { setter ->
                setter.set(1, Value.fromValue("foo"))
            }
        }
        assertTrue(e.message!!.contains("Invalid slotNo 1"))
    }
}
