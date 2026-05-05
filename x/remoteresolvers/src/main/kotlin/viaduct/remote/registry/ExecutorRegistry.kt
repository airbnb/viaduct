package viaduct.remote.registry

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.spi.NodeResolverExecutor

/**
 * Registry of [NodeResolverExecutor] instances keyed by GraphQL type name.
 *
 * Type names are stable across JVMs, so both the proxy (which registers at proxy
 * creation) and the remote service (which registers at tenant bootstrap) can use
 * them as a shared identifier.
 */
object ExecutorRegistry {
    private val executors = ConcurrentHashMap<String, NodeResolverExecutor>()

    /** Registers an executor under its type name and returns that name as its handle. */
    fun register(executor: NodeResolverExecutor): String {
        val id = executor.typeName
        executors[id] = executor
        return id
    }

    fun get(id: String): NodeResolverExecutor? = executors[id]

    fun unregister(id: String): NodeResolverExecutor? = executors.remove(id)

    /** Clears all entries. Intended for tests. */
    fun clear() = executors.clear()
}
