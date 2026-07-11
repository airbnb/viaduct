package viaduct.remote.registry

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * In-process registry of resolver executors keyed by a stable, cross-JVM string id.
 *
 * The id is stable across JVMs, so both the proxy (which registers when a resolver is
 * wrapped at bootstrap) and the remote service (which registers at tenant bootstrap) can
 * use it as a shared identifier. [NodeExecutorRegistry] and [FieldExecutorRegistry] are
 * separate instances so the two keyspaces and value types never mix.
 */
sealed class ExecutorRegistry<T>(private val idOf: (T) -> String) {
    private val log = LoggerFactory.getLogger(ExecutorRegistry::class.java)
    private val executors = ConcurrentHashMap<String, T>()

    /** Registers an [executor] under its stable id and returns that id as its handle. */
    fun register(executor: T): String {
        val id = idOf(executor)
        // Later-wins (mirrors the engine's DispatcherRegistryFactory). Warn only when a *different*
        // executor is displaced — that shadows a tenant resolver (e.g. a built-in like Query.node) and
        // should be visible in logs. Re-registering the same instance (an idempotent re-bootstrap) is
        // benign, so log it at debug to avoid flooding logs when bootstrap re-runs.
        val previous = executors.put(id, executor)
        if (previous != null && previous !== executor) {
            log.warn("Resolver executor id '{}' was already registered; overwriting the previous executor", id)
        } else if (previous != null) {
            log.debug("Resolver executor id '{}' re-registered with the same executor", id)
        }
        return id
    }

    fun get(id: String): T? = executors[id]

    fun unregister(id: String): T? = executors.remove(id)

    /** Clears all entries. Intended for tests. */
    fun clear() = executors.clear()
}
