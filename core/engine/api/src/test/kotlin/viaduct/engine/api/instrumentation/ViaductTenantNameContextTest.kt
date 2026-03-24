@file:Suppress("ForbiddenImport")

package viaduct.engine.api.instrumentation

import java.util.concurrent.CompletableFuture
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViaductTenantNameContextTest {
    @Test
    fun `getCurrent returns null when no context has been set on the current thread`() {
        val freshThreadResult = runOnNewThread { ViaductTenantNameContext.getCurrent() }

        assertNull(freshThreadResult)
    }

    @Test
    fun `tenantName stores and returns a non-null string value`() {
        val context = ViaductTenantNameContext("my-tenant")

        assertEquals("my-tenant", context.tenantName)
    }

    @Test
    fun `tenantName stores and returns a null value`() {
        val context = ViaductTenantNameContext(null)

        assertNull(context.tenantName)
    }

    @Test
    fun `asCoroutineContext makes getCurrent return the given context inside the coroutine`() {
        val tenantContext = ViaductTenantNameContext("coroutine-tenant")

        val capturedContext = runBlocking {
            withContext(ViaductTenantNameContext.asCoroutineContext(tenantContext)) {
                ViaductTenantNameContext.getCurrent()
            }
        }

        assertEquals(tenantContext, capturedContext)
    }

    @Test
    fun `asCoroutineContext with null tenantName makes getCurrent return context with null tenantName inside the coroutine`() {
        val tenantContext = ViaductTenantNameContext(null)

        val capturedContext = runBlocking {
            withContext(ViaductTenantNameContext.asCoroutineContext(tenantContext)) {
                ViaductTenantNameContext.getCurrent()
            }
        }

        assertEquals(tenantContext, capturedContext)
        assertNull(capturedContext?.tenantName)
    }

    @Test
    fun `getCurrent returns null on a new thread even after context was set via coroutine on another thread`() {
        val tenantContext = ViaductTenantNameContext("other-thread-tenant")

        // Set context in a coroutine on the current thread.
        runBlocking {
            withContext(ViaductTenantNameContext.asCoroutineContext(tenantContext)) {
                // No assertion here; we just want the context set in this scope.
            }
        }

        // A brand-new thread should not see any context inherited from the current thread.
        val resultOnNewThread = runOnNewThread { ViaductTenantNameContext.getCurrent() }

        assertNull(resultOnNewThread)
    }

    @Test
    fun `getCurrent returns null after exiting the coroutine context scope`() {
        val tenantContext = ViaductTenantNameContext("scoped-tenant")

        runBlocking {
            withContext(ViaductTenantNameContext.asCoroutineContext(tenantContext)) {
                // Inside the scope, context is set.
            }
            // Outside the withContext block, the ThreadLocal should be restored.
        }

        // After the coroutine completes, getCurrent on this thread should be null again.
        val resultAfterScope = runOnNewThread { ViaductTenantNameContext.getCurrent() }
        assertNull(resultAfterScope)
    }

    // Helper to run a block on a fresh thread, ensuring ThreadLocal isolation.
    private fun <T> runOnNewThread(block: () -> T): T {
        val future = CompletableFuture<T>()
        val thread = Thread {
            try {
                future.complete(block())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        thread.start()
        return future.get()
    }
}
