@file:Suppress("ForbiddenImport")

package viaduct.api

import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ViaductTenantResolverExceptionTest {
    @Test
    fun getResolversCallChain() {
        val exception = ViaductTenantResolverException(
            cause = ViaductTenantResolverException(
                cause = ViaductTenantResolverException(
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
            assertThrows<ViaductFrameworkException> {
                wrapResolveException("ResolverA") {
                    throw ViaductFrameworkException("a framework exception occurred")
                }
            }
        }

    @Test
    fun testWrapTenantException(): Unit =
        runBlocking {
            assertThrows<ViaductTenantResolverException> {
                wrapResolveException("ResolverA") {
                    throw InvocationTargetException(RuntimeException("a tenant exception occurred"))
                }
            }
        }
}
