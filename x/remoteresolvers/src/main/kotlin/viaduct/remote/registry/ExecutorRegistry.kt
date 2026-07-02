package viaduct.remote.registry

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process registry of resolver executors keyed by a stable, cross-JVM string id.
 *
 * The id is stable across JVMs, so both the proxy (which registers when a resolver is
 * wrapped at bootstrap) and the remote service (which registers at tenant bootstrap) can
 * use it as a shared identifier. [NodeExecutorRegistry] and [FieldExecutorRegistry] are
 * separate instances so the two keyspaces and value types never mix.
 */
sealed class ExecutorRegistry<T>(private val idOf: (T) -> String) {
    private val executors = ConcurrentHashMap<String, T>()

    /** Registers an [executor] under its stable id and returns that id as its handle. */
    fun register(executor: T): String {
        val id = idOf(executor)
        executors[id] = executor
        return id
    }

    fun get(id: String): T? = executors[id]

    fun unregister(id: String): T? = executors.remove(id)

    /** Clears all entries. Intended for tests. */
    fun clear() = executors.clear()
}
