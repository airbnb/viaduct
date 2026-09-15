@file:Suppress("ForbiddenImport")

package viaduct.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.errors.DataFailureException
import viaduct.errors.FrameworkException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleAccessorErrors
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.errors.nullOnDataFailure

class ExceptionsTest {
    private class TestDataFailureException : RuntimeException(), DataFailureException

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
    fun `test handleFrameworkErrors wraps cancellation like other synchronous failures`() {
        val exception = CancellationException("cancelled")
        val thrown = assertThrows(FrameworkException::class.java) {
            handleFrameworkErrors("Test message") {
                throw exception
            }
        }
        assertEquals(exception, thrown.cause)
    }

    @Test
    fun `test handleAccessorErrors preserves cancellation`() {
        val exception = CancellationException("cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            handleAccessorErrors("Test message") {
                throw exception
            }
        }
        assertEquals(exception, thrown)
    }

    @Test
    fun `test nullOnDataFailure catches resolver failures`() {
        assertNull(
            nullOnDataFailure<String> {
                throw TenantResolverException(IllegalStateException("failed"), "Widget.value")
            }
        )
    }

    @Test
    fun `test nullOnDataFailure catches a wrapped data failure`() {
        assertNull(
            nullOnDataFailure<String> {
                throw FrameworkException(
                    "field failed",
                    FrameworkException("fetch failed", TestDataFailureException())
                )
            }
        )
    }

    @Test
    fun `test nullOnDataFailure preserves cancellation through passthrough wrappers`() {
        val exception = CancellationException("cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            nullOnDataFailure<String> {
                throw FrameworkException("field failed", FrameworkException("fetch failed", exception))
            }
        }
        assertEquals(exception, thrown)
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
