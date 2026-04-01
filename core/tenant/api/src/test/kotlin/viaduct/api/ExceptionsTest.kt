@file:Suppress("ForbiddenImport")

package viaduct.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleFrameworkErrorsSuspend

class ExceptionsTest {
    @Test
    fun `test handleFrameworkErrors with TenantException`() {
        val exception = TenantUsageException("Tenant error")
        val thrown = assertThrows(TenantUsageException::class.java) {
            handleFrameworkErrors("Test message") {
                throw exception
            }
        }
        assertEquals(exception, thrown)
    }

    @Test
    fun `test handleFrameworkErrors passes through FrameworkException unchanged`() {
        val exception = FrameworkException("Framework error")
        val thrown = assertThrows(FrameworkException::class.java) {
            handleFrameworkErrors("Test message") {
                throw exception
            }
        }
        assertEquals(exception, thrown)
    }

    @Test
    fun `test handleFrameworkErrors with other exception`() {
        val exception = RuntimeException("Runtime error")
        val thrown = assertThrows(FrameworkException::class.java) {
            handleFrameworkErrors("Test message") {
                throw exception
            }
        }
        assertEquals("Test message (java.lang.RuntimeException: Runtime error)", thrown.message)
        assertEquals(exception, thrown.cause)
    }

    @Test
    fun `test handleFrameworkErrorsSuspend with TenantException`() {
        val exception = TenantUsageException("Tenant error")
        val thrown = assertThrows(TenantUsageException::class.java) {
            runBlocking {
                handleFrameworkErrorsSuspend("Test message") {
                    throw exception
                }
            }
        }
        assertEquals(exception, thrown)
    }

    @Test
    fun `test handleFrameworkErrorsSuspend passes through FrameworkException unchanged`() {
        val exception = FrameworkException("Framework error")
        val thrown = assertThrows(FrameworkException::class.java) {
            runBlocking {
                handleFrameworkErrorsSuspend("Test message") {
                    throw exception
                }
            }
        }
        assertEquals(exception, thrown)
    }

    @Test
    fun `test handleFrameworkErrorsSuspend with other exception`() {
        val exception = RuntimeException("Runtime error")
        val thrown = assertThrows(FrameworkException::class.java) {
            runBlocking {
                handleFrameworkErrorsSuspend("Test message") {
                    throw exception
                }
            }
        }
        assertEquals("Test message (java.lang.RuntimeException: Runtime error)", thrown.message)
        assertEquals(exception, thrown.cause)
    }
}
