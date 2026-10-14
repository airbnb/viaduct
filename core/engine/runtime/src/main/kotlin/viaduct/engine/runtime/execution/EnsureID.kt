package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generate identifiers for defer delivery groups.
 *
 * Defined by Section 6.4.7, EnsureID.
 */
class EnsureID {
    private val ids = ConcurrentHashMap<DeferDeliveryGroup, String>()
    private val nextId = AtomicInteger()

    operator fun invoke(group: DeferDeliveryGroup): String =
        ids.computeIfAbsent(group) {
            nextId.getAndIncrement().toString()
        }
}
