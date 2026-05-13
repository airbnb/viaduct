package viaduct.engine.api.instrumentation

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asContextElement

/**
 * Coroutine-context element that carries the name of the currently executing tenant.
 *
 * The tenant name is stored in a [ThreadLocal] so that it is accessible from both coroutine and
 * non-coroutine code. Use [asCoroutineContext] to install a value for the duration of a coroutine,
 * and [getCurrent] to read the active value from any thread.
 *
 * @property tenantName The name of the tenant currently executing, or `null` if no tenant context
 *   is active.
 */
class ViaductTenantNameContext(
    val tenantName: String?
) {
    companion object {
        private val currentContext: ThreadLocal<ViaductTenantNameContext> = ThreadLocal.withInitial { null }

        /**
         * Returns the [ViaductTenantNameContext] installed on the current thread, or `null` if none
         * has been set.
         */
        fun getCurrent(): ViaductTenantNameContext? {
            return currentContext.get()
        }

        /**
         * Wraps [newValue] as a [CoroutineContext.Element] that installs it into the thread-local
         * for the duration of the coroutine. Pass the result to `withContext { ... }` to scope the
         * tenant name to a specific coroutine block.
         */
        fun asCoroutineContext(newValue: ViaductTenantNameContext): CoroutineContext.Element {
            return currentContext.asContextElement(newValue)
        }
    }
}
