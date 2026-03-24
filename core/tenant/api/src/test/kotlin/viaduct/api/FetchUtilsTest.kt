@file:Suppress("ForbiddenImport")

package viaduct.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FetchUtilsTest {
    @Test
    fun `fetchOrNull returns value on success`() =
        runBlocking {
            val result = fetchOrNull { "success" }
            assertEquals("success", result)
        }

    @Test
    fun `fetchOrNull returns null on exception`() =
        runBlocking {
            val result = fetchOrNull { throw RuntimeException("error") }
            assertNull(result)
        }

    @Test
    fun `fetchOrNull rethrows CancellationException`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                fetchOrNull { throw CancellationException("cancelled") }
            }
        }
    }

    @Test
    fun `fetchOrNull handles null return value`() =
        runBlocking {
            val result: String? = fetchOrNull { null }
            assertNull(result)
        }

    @Test
    fun `fetchOrDefault returns value on success`() =
        runBlocking {
            val result = fetchOrDefault("default") { "success" }
            assertEquals("success", result)
        }

    @Test
    fun `fetchOrDefault returns default on exception`() =
        runBlocking {
            val result = fetchOrDefault("default") { throw RuntimeException("error") }
            assertEquals("default", result)
        }

    @Test
    fun `fetchOrDefault rethrows CancellationException`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                fetchOrDefault("default") { throw CancellationException("cancelled") }
            }
        }
    }

    @Test
    fun `fetchOrDefault preserves null return from block`() =
        runBlocking {
            val result: String? = fetchOrDefault("default") { null }
            assertNull(result)
        }
}
