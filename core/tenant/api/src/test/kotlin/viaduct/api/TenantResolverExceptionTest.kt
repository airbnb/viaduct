@file:Suppress("ForbiddenImport")

package viaduct.api

import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.errors.FrameworkException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsResultSuspend
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend

class TenantResolverExceptionTest {
    @Test
    fun getResolversCallChain() {
        val exception = TenantResolverException(
            cause = TenantResolverException(
                cause = TenantResolverException(
                    cause = RuntimeException(),
                    resolver = "ResolverC",
                ),
                resolver = "ResolverB",
            ),
            resolver = "ResolverA",
        )

        val callChain = exception.resolversCallChain
        assertEquals("ResolverA > ResolverB > ResolverC", callChain)
    }

    @Test
    fun testWrapFrameworkException(): Unit =
        runBlocking {
            assertThrows<FrameworkException> {
                handleTenantErrorsSuspend("ResolverA") {
                    throw FrameworkException("a framework exception occurred")
                }
            }
        }

    @Test
    fun testWrapUnhandledException(): Unit =
        runBlocking {
            assertThrows<TenantResolverException> {
                handleTenantErrorsSuspend("ResolverA") {
                    throw InvocationTargetException(RuntimeException("a tenant exception occurred"))
                }
            }
        }

    @Test
    fun testWrapTenantUsageException(): Unit =
        runBlocking {
            val thrown = assertThrows<TenantResolverException> {
                handleTenantErrorsSuspend("ResolverA") {
                    throw TenantUsageException("tenant api misuse")
                }
            }
            assertEquals("ResolverA", thrown.resolver)
            assertInstanceOf(TenantUsageException::class.java, thrown.cause)
            assertEquals("tenant api misuse", thrown.cause.message)
        }

    @Test
    fun testHandleTenantErrorsResultSuspendSuccess(): Unit =
        runBlocking {
            val result = handleTenantErrorsResultSuspend("ResolverA") { "ok" }

            assertTrue(result.isSuccess)
            assertEquals("ok", result.getOrNull())
        }

    @Test
    fun testResultOfSuspendPreservesUnhandledException(): Unit =
        runBlocking {
            val exception = RuntimeException("plain failure")
            val result = resultOfSuspend<String> {
                throw exception
            }

            assertTrue(result.isFailure)
            assertEquals(exception, result.exceptionOrNull())
        }

    @Test
    fun testResultOfSuspendAppliesExceptionMapper(): Unit =
        runBlocking {
            val result = resultOfSuspend<String>(
                mapException = { e -> IllegalStateException("mapped: ${e.message}", e) }
            ) {
                throw RuntimeException("plain failure")
            }

            assertTrue(result.isFailure)
            val thrown = assertInstanceOf(IllegalStateException::class.java, result.exceptionOrNull())
            assertEquals("mapped: plain failure", thrown.message)
            assertInstanceOf(RuntimeException::class.java, thrown.cause)
        }

    @Test
    fun testHandleTenantErrorsResultSuspendPreservesAttributedFailures(): Unit =
        runBlocking {
            val tenantUsage = TenantUsageException("tenant api misuse")
            val framework = FrameworkException("framework failure")

            val tenantResult = handleTenantErrorsResultSuspend("ResolverA") { throw tenantUsage }
            val frameworkResult = handleTenantErrorsResultSuspend("ResolverA") { throw framework }

            assertTrue(tenantResult.isFailure)
            assertTrue(frameworkResult.isFailure)
            assertFalse(tenantResult.exceptionOrNull() is TenantResolverException)
            assertFalse(frameworkResult.exceptionOrNull() is TenantResolverException)
            assertEquals(tenantUsage, tenantResult.exceptionOrNull())
            assertEquals(framework, frameworkResult.exceptionOrNull())
        }

    @Test
    fun testHandleTenantErrorsResultSuspendWrapsUnhandledException(): Unit =
        runBlocking {
            val result = handleTenantErrorsResultSuspend("ResolverA") {
                throw RuntimeException("a tenant exception occurred")
            }

            assertTrue(result.isFailure)
            val thrown = assertInstanceOf(TenantResolverException::class.java, result.exceptionOrNull())
            assertEquals("ResolverA", thrown.resolver)
            assertInstanceOf(RuntimeException::class.java, thrown.cause)
            assertEquals("a tenant exception occurred", thrown.cause?.message)
        }
}
