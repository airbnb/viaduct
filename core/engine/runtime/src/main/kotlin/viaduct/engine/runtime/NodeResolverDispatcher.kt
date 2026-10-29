package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext

/**
 * Class that delegates to a data loader to call [NodeResolverExecutor]
 */
interface NodeResolverDispatcher {
    /** The metadata associated with this resolver **/
    val resolverMetadata: ResolverMetadata

    /** Whether the underlying resolver's result varies based on the requested selections. */
    val isSelective: Boolean

    suspend fun resolve(
        id: String,
        selections: EngineSelectionSet,
        context: EngineExecutionContext,
        instrumentationContext: ResolverInstrumentationContext? = null,
    ): EngineObjectData
}
