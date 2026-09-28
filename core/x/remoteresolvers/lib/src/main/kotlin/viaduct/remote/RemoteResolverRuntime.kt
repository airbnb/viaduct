package viaduct.remote

import org.slf4j.LoggerFactory
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor

/** One complete schema and executor generation used by the remote resolver service. */
class RemoteResolverRuntime(
    val schema: EngineSchema,
    nodeExecutors: Iterable<NodeResolverExecutor> = emptyList(),
    fieldExecutors: Iterable<FieldResolverExecutor> = emptyList(),
) {
    val nodeExecutors: Map<String, NodeResolverExecutor> =
        executorMap(nodeExecutors, NodeResolverExecutor::typeName)
    val fieldExecutors: Map<String, FieldResolverExecutor> =
        executorMap(fieldExecutors, FieldResolverExecutor::resolverId)

    private fun <T> executorMap(
        executors: Iterable<T>,
        idOf: (T) -> String,
    ): Map<String, T> =
        buildMap {
            executors.forEach { executor ->
                val id = idOf(executor)
                val previous = put(id, executor)
                if (previous != null && previous !== executor) {
                    log.warn("Resolver executor id '{}' was already present; replacing the previous executor", id)
                }
            }
        }

    private companion object {
        private val log = LoggerFactory.getLogger(RemoteResolverRuntime::class.java)
    }
}

fun interface RemoteResolverRuntimeProvider {
    fun get(): RemoteResolverRuntime
}
