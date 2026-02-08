@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.UnsetSelectionException
import viaduct.engine.api.mocks.mkSchema

class SyncProxyEngineObjectDataTest {
    private val schema = mkSchema(
        """
            type Obj { x: Int, y: String }
            extend type Query { x: Int }
        """.trimIndent()
    )
    private val obj = schema.schema.getObjectType("Obj")

    // ============================================================================
    // Basic functionality tests
    // ============================================================================

    @Test
    fun `get -- returns stored values`() {
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to 1, "y" to "hello")
        )

        assertEquals(1, eod.get("x"))
        assertEquals("hello", eod.get("y"))
    }

    @Test
    fun `get -- returns null for null values`() {
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to null)
        )

        assertEquals(null, eod.get("x"))
    }

    @Test
    fun `get -- throws UnsetSelectionException for missing selection`() {
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to 1)
        )

        assertThrows<UnsetSelectionException> {
            eod.get("missing")
        }
    }

    @Test
    fun `get -- uses custom error message template`() {
        val customMessage = "add it to @Resolver's objectValueFragment"
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to 1),
            customMessage
        )

        val exception = assertThrows<UnsetSelectionException> {
            eod.get("missing")
        }
        assert(exception.message!!.contains(customMessage)) {
            "Expected message to contain custom error, but was: ${exception.message}"
        }
    }

    @Test
    fun `getOrNull -- returns stored values`() {
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to 1)
        )

        assertEquals(1, eod.getOrNull("x"))
    }

    @Test
    fun `getOrNull -- returns null for missing selection`() {
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to 1)
        )

        assertEquals(null, eod.getOrNull("missing"))
    }

    @Test
    fun `getSelections -- returns all keys`() {
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to 1, "y" to "hello")
        )

        assertEquals(setOf("x", "y"), eod.getSelections().toSet())
    }

    @Test
    fun `graphQLObjectType -- returns the object type`() {
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to 1)
        )

        assertSame(obj, eod.graphQLObjectType)
    }

    // ============================================================================
    // Error handling tests - stored exceptions are thrown on access
    // ============================================================================

    @Test
    fun `get -- throws stored exception`() {
        val storedException = IllegalStateException("test error")
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to storedException)
        )

        val thrown = assertThrows<IllegalStateException> {
            eod.get("x")
        }
        assertSame(storedException, thrown)
    }

    @Test
    fun `get -- throws stored FieldErrorsException`() {
        val storedException = FieldErrorsException(emptyList())
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to storedException)
        )

        val thrown = assertThrows<FieldErrorsException> {
            eod.get("x")
        }
        assertSame(storedException, thrown)
    }

    @Test
    fun `getOrNull -- throws stored exception`() {
        val storedException = RuntimeException("access denied")
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to storedException)
        )

        val thrown = assertThrows<RuntimeException> {
            eod.getOrNull("x")
        }
        assertSame(storedException, thrown)
    }

    @Test
    fun `get -- stored exception preserves original stack trace`() {
        // Create exception with a known stack trace
        val storedException = IllegalAccessException("no access")
        val originalStackTrace = storedException.stackTrace

        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to storedException)
        )

        val thrown = assertThrows<IllegalAccessException> {
            eod.get("x")
        }

        // The rethrown exception should have the same stack trace as the original
        assertEquals(originalStackTrace.toList(), thrown.stackTrace.toList())
    }

    @Test
    fun `mixed values and errors -- only errors throw on access`() {
        val storedException = RuntimeException("error on y")
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf(
                "x" to 42,
                "y" to storedException
            )
        )

        // x should return normally
        assertEquals(42, eod.get("x"))

        // y should throw
        val thrown = assertThrows<RuntimeException> {
            eod.get("y")
        }
        assertSame(storedException, thrown)
    }

    @Test
    fun `getSelections -- includes keys with stored exceptions`() {
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf(
                "x" to 1,
                "y" to RuntimeException("error")
            )
        )

        // Both keys should be in selections, even though y has an error
        assertEquals(setOf("x", "y"), eod.getSelections().toSet())
    }

    // ============================================================================
    // Suspend method tests - verify delegation to sync methods
    // ============================================================================

    @Test
    fun `fetch -- delegates to get`() =
        runBlocking {
            val eod = SyncProxyEngineObjectData(
                obj,
                mapOf("x" to 1, "y" to "hello")
            )

            assertEquals(1, eod.fetch("x"))
            assertEquals("hello", eod.fetch("y"))
        }

    @Test
    fun `fetch -- throws UnsetSelectionException for missing selection`() =
        runBlocking {
            val eod = SyncProxyEngineObjectData(
                obj,
                mapOf("x" to 1)
            )

            assertThrows<UnsetSelectionException> {
                eod.fetch("missing")
            }
        }

    @Test
    fun `fetch -- throws stored exception`() =
        runBlocking {
            val storedException = RuntimeException("test error")
            val eod = SyncProxyEngineObjectData(
                obj,
                mapOf("x" to storedException)
            )

            val thrown = assertThrows<RuntimeException> {
                eod.fetch("x")
            }
            assertSame(storedException, thrown)
        }

    @Test
    fun `fetchOrNull -- delegates to getOrNull`() =
        runBlocking {
            val eod = SyncProxyEngineObjectData(
                obj,
                mapOf("x" to 1)
            )

            assertEquals(1, eod.fetchOrNull("x"))
            assertNull(eod.fetchOrNull("missing"))
        }

    @Test
    fun `fetchOrNull -- throws stored exception`() =
        runBlocking {
            val storedException = RuntimeException("test error")
            val eod = SyncProxyEngineObjectData(
                obj,
                mapOf("x" to storedException)
            )

            val thrown = assertThrows<RuntimeException> {
                eod.fetchOrNull("x")
            }
            assertSame(storedException, thrown)
        }

    @Test
    fun `fetchSelections -- delegates to getSelections`() =
        runBlocking {
            val eod = SyncProxyEngineObjectData(
                obj,
                mapOf("x" to 1, "y" to "hello")
            )

            assertEquals(setOf("x", "y"), eod.fetchSelections().toSet())
        }

    // ============================================================================
    // toString test
    // ============================================================================

    @Test
    fun `toString -- includes type name and data`() {
        val eod = SyncProxyEngineObjectData(
            obj,
            mapOf("x" to 1, "y" to "hello")
        )

        val str = eod.toString()
        assertTrue(str.contains("Obj")) { "Expected type name 'Obj' in toString: $str" }
        assertTrue(str.contains("SyncProxyEngineObjectData")) { "Expected class name in toString: $str" }
    }
}
