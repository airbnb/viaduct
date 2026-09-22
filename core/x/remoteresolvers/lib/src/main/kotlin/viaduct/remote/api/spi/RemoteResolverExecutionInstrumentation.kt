package viaduct.remote.api.spi

import viaduct.engine.api.ResolverMetadata

/** A resolver's own `resolve`/`batchResolve` call, invoked with no arguments. Defined locally so this SPI doesn't depend on the in-process instrumentation package. */
fun interface RemoteResolverFunction<T> {
    suspend fun resolve(): T
}

/** SPI for observing/wrapping a remote resolver's own execution on the RRS side. */
interface RemoteResolverExecutionInstrumentation {
    data class RemoteResolverExecutionParameters(val resolverMetadata: ResolverMetadata)

    /** Wraps one resolver invocation; implementations may keep state active across the suspend call, e.g. a tracer holding a span open. */
    suspend fun <T> instrumentRemoteResolverExecution(
        resolver: RemoteResolverFunction<T>,
        parameters: RemoteResolverExecutionParameters,
    ): T

    companion object {
        val NO_OP: RemoteResolverExecutionInstrumentation =
            object : RemoteResolverExecutionInstrumentation {
                override suspend fun <T> instrumentRemoteResolverExecution(
                    resolver: RemoteResolverFunction<T>,
                    parameters: RemoteResolverExecutionParameters,
                ): T = resolver.resolve()
            }
    }
}
