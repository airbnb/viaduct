@file:Suppress("ForbiddenImport")

package viaduct.api

import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.errors.FrameworkException
import viaduct.errors.TenantResolverException
import viaduct.errors.wrapResolveException

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
                wrapResolveException("ResolverA") {
                    throw FrameworkException("a framework exception occurred")
                }
            }
        }

    @Test
    fun testWrapTenantException(): Unit =
        runBlocking {
            assertThrows<TenantResolverException> {
                wrapResolveException("ResolverA") {
                    throw InvocationTargetException(RuntimeException("a tenant exception occurred"))
                }
            }
        }
}
