@file:Suppress("ForbiddenImport")

package viaduct.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantAPIErrors
import viaduct.errors.handleTenantAPIErrorsSuspend

class ExceptionsTest {
    @Test
    fun `test handleTenantAPIErrors with TenantException`() {
        val exception = TenantUsageException("Tenant error")
        val thrown = assertThrows(TenantUsageException::class.java) {
            handleTenantAPIErrors("Test message") {
                throw exception
            }
        }
        assertEquals(exception, thrown)
    }

    @Test
    fun `test handleTenantAPIErrors with other exception`() {
        val exception = RuntimeException("Runtime error")
        val thrown = assertThrows(FrameworkException::class.java) {
            handleTenantAPIErrors("Test message") {
                throw exception
            }
        }
        assertEquals("Test message (java.lang.RuntimeException: Runtime error)", thrown.message)
        assertEquals(exception, thrown.cause)
    }

    @Test
    fun `test handleTenantAPIErrorsSuspend with TenantException`() {
        val exception = TenantUsageException("Tenant error")
        val thrown = assertThrows(TenantUsageException::class.java) {
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
        val thrown = assertThrows(FrameworkException::class.java) {
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
