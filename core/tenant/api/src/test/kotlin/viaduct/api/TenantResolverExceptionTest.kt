@file:Suppress("ForbiddenImport")

package viaduct.api

import io.kotest.matchers.types.shouldBeInstanceOf
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.errors.FrameworkException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.errors.handleTenantErrors
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
            thrown.cause.shouldBeInstanceOf<TenantUsageException>()
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
            val thrown = result.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
            assertEquals("mapped: plain failure", thrown.message)
            thrown.cause.shouldBeInstanceOf<RuntimeException>()
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
    @Suppress("UNNECESSARY_SAFE_CALL")
    fun testHandleTenantErrorsResultSuspendWrapsUnhandledException(): Unit =
        runBlocking {
            val result = handleTenantErrorsResultSuspend("ResolverA") {
                throw RuntimeException("a tenant exception occurred")
            }

            assertTrue(result.isFailure)
            val thrown = result.exceptionOrNull().shouldBeInstanceOf<TenantResolverException>()
            assertEquals("ResolverA", thrown.resolver)
            thrown.cause.shouldBeInstanceOf<RuntimeException>()
            assertEquals("a tenant exception occurred", thrown.cause?.message)
        }

    @Test
    fun `tenant handlers attribute tenant Errors with the original cause`(): Unit =
        runBlocking {
            listOf(AssertionError("assert"), NotImplementedError(), NoSuchMethodError("missing")).forEach { error ->
                assertSame(error, assertThrows<TenantResolverException> { handleTenantErrors("ResolverA") { throw error } }.cause)
                assertSame(error, assertThrows<TenantResolverException> { handleTenantErrorsSuspend("ResolverA") { throw error } }.cause)
                val returned = handleTenantErrorsResultSuspend<Unit>("ResolverA") { throw error }.exceptionOrNull()
                assertSame(error, assertInstanceOf(TenantResolverException::class.java, returned).cause)
            }
        }

    @Suppress("DEPRECATION") // ThreadDeath
    @Test
    fun `error handlers propagate JVM failures and interruption`(): Unit =
        runBlocking {
            listOf(OutOfMemoryError("oom"), ThreadDeath(), InterruptedException("interrupted")).forEach { failure ->
                assertSame(failure, assertThrows<Throwable> { handleTenantErrors("ResolverA") { throw failure } })
                assertSame(failure, assertThrows<Throwable> { handleTenantErrorsSuspend("ResolverA") { throw failure } })
                assertSame(failure, assertThrows<Throwable> { handleTenantErrorsResultSuspend<Unit>("ResolverA") { throw failure } })
                assertSame(failure, assertThrows<Throwable> { handleFrameworkErrors("query") { throw failure } })
                assertSame(failure, assertThrows<Throwable> { handleFrameworkErrorsSuspend("query") { throw failure } })
            }
        }

    @Test
    fun `suspend tenant handlers propagate caller cancellation instead of attributing it`(): Unit =
        runBlocking {
            val failures = mutableListOf<Throwable?>()
            launch {
                coroutineContext.job.cancel()
                failures += runCatching { handleTenantErrorsSuspend("ResolverA") { throw IllegalStateException("after cancel") } }.exceptionOrNull()
                failures += runCatching { handleTenantErrorsResultSuspend<Unit>("ResolverA") { throw IllegalStateException("after cancel") } }.exceptionOrNull()
            }.join()

            assertEquals(2, failures.size)
            failures.forEach { assertInstanceOf(CancellationException::class.java, it) }
        }
}
