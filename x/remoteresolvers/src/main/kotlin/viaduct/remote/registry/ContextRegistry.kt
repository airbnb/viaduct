package viaduct.remote.registry

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.EngineExecutionContext

/**
 * In-process registry that hands out opaque handles to [EngineExecutionContext]
 * instances so remote callers can reference them without serializing context state.
 *
 * Handles are per-process and ephemeral; callers must unregister when the owning
 * request completes. Each [register] call returns a fresh handle even for the
 * same [EngineExecutionContext] instance, so overlapping RPCs sharing one context
 * cannot remove each other's entry during their `finally` cleanup.
 */
object ContextRegistry {
    private val contexts = ConcurrentHashMap<String, EngineExecutionContext>()

    fun register(context: EngineExecutionContext): String {
        val handle = UUID.randomUUID().toString()
        contexts[handle] = context
        return handle
    }

    fun get(handle: String): EngineExecutionContext? = contexts[handle]

    fun unregister(handle: String): EngineExecutionContext? = contexts.remove(handle)

    /** Number of live handles. Intended for tests, e.g. to assert no handles leaked. */
    val size: Int get() = contexts.size

    /** Clears all entries. Intended for tests. */
    fun clear() {
        contexts.clear()
    }
}
