@file:Suppress("ForbiddenImport")

package viaduct.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExceptionsTest {
    @Test
    fun `test handleTenantAPIErrors with ViaductTenantException`() {
        val exception = ViaductTenantUsageException("Tenant error")
        val thrown = assertThrows(ViaductTenantUsageException::class.java) {
            handleTenantAPIErrors("Test message") {
                throw exception
            }
        }
        assertEquals(exception, thrown)
    }

    @Test
    fun `test handleTenantAPIErrors with other exception`() {
        val exception = RuntimeException("Runtime error")
        val thrown = assertThrows(ViaductFrameworkException::class.java) {
            handleTenantAPIErrors("Test message") {
                throw exception
            }
        }
        assertEquals("Test message (java.lang.RuntimeException: Runtime error)", thrown.message)
        assertEquals(exception, thrown.cause)
    }

    @Test
    fun `test handleTenantAPIErrorsSuspend with ViaductTenantException`() {
        val exception = ViaductTenantUsageException("Tenant error")
        val thrown = assertThrows(ViaductTenantUsageException::class.java) {
            runBlocking {
                handleTenantAPIErrorsSuspend("Test message") {
                    throw exception
                }
            }
        }
        assertEquals(exception, thrown)
    }

    @Test
    fun `test handleTenantAPIErrorsSuspend with other exception`() {
        val exception = RuntimeException("Runtime error")
        val thrown = assertThrows(ViaductFrameworkException::class.java) {
            runBlocking {
                handleTenantAPIErrorsSuspend("Test message") {
                    throw exception
                }
            }
        }
        assertEquals("Test message (java.lang.RuntimeException: Runtime error)", thrown.message)
        assertEquals(exception, thrown.cause)
    }
}
