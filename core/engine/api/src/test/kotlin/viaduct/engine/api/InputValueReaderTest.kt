package viaduct.engine.api

import kotlin.IllegalArgumentException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InputValueReaderTest {
    private fun mk(path: String) = InputValueReader(path.split("."))

    @Test
    fun `empty`() {
        // empty path
        assertThrows<IllegalArgumentException> {
            InputValueReader(emptyList())
        }

        // empty map
        assertNull(
            mk("x").read(emptyMap())
        )
    }

    @Test
    fun `single-element paths`() {
        // terminating element is simple
        assertEquals(
            1,
            mk("x").read(mapOf("x" to 1))
        )

        // terminating element is list
        assertEquals(
            listOf(1),
            mk("x").read(mapOf("x" to listOf(1)))
        )

        // terminating element is map
        assertEquals(
            mapOf("y" to 1),
            mk("x").read(mapOf("x" to mapOf("y" to 1)))
        )
    }

    @Test
    fun `multi-element paths`() {
        // terminating element is simple
        assertEquals(
            1,
            mk("x.y.z").read(mapOf("x" to mapOf("y" to mapOf("z" to 1))))
        )

        // terminating element is list
        assertEquals(
            listOf(1),
            mk("x.y.z").read(mapOf("x" to mapOf("y" to mapOf("z" to listOf(1)))))
        )

        // terminating element is map
        assertEquals(
            mapOf("a" to "A"),
            mk("x.y.z").read(mapOf("x" to mapOf("y" to mapOf("z" to mapOf("a" to "A")))))
        )
    }

    @Test
    fun `traverse through a non-map`() {
        // traverse through a simple value
        assertThrows<IllegalStateException> {
            mk("x.y").read(mapOf("x" to 1))
        }

        // traverse through a list value
        assertThrows<IllegalStateException> {
            mk("x.y").read(mapOf("x" to listOf(1)))
        }

        // traverse through a null value
        assertNull(
            mk("x.y").read(mapOf("x" to null))
        )
    }
}
