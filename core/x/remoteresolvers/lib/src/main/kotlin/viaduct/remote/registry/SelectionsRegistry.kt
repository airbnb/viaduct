package viaduct.remote.registry

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.EngineSelectionSet

/**
 * In-process registry that hands out opaque handles to [EngineSelectionSet] instances.
 *
 * Handles are ephemeral; callers must unregister when the owning request completes.
 */
object SelectionsRegistry {
    private val selections = ConcurrentHashMap<String, EngineSelectionSet>()

    fun register(selectionSet: EngineSelectionSet): String {
        val handle = UUID.randomUUID().toString()
        selections[handle] = selectionSet
        return handle
    }

    fun get(handle: String): EngineSelectionSet? = selections[handle]

    fun unregister(handle: String): EngineSelectionSet? = selections.remove(handle)

    /** Number of live handles. Intended for tests, e.g. to assert no handles leaked. */
    val size: Int get() = selections.size

    /** Clears all entries. Intended for tests. */
    fun clear() = selections.clear()
}
