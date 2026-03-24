package viaduct.engine.api.instrumentation

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asContextElement

class ViaductTenantNameContext(
    val tenantName: String?
) {
    companion object {
        private val currentContext: ThreadLocal<ViaductTenantNameContext> = ThreadLocal.withInitial { null }

        fun getCurrent(): ViaductTenantNameContext? {
            return currentContext.get()
        }

        fun asCoroutineContext(newValue: ViaductTenantNameContext): CoroutineContext.Element {
            return currentContext.asContextElement(newValue)
        }
    }
}
