package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ResolverMetadata

/**
 * Class that delegates to a data loader to call [NodeResolverExecutor]
 */
interface NodeResolverDispatcher {
    /** The metadata associated with this resolver **/
    val resolverMetadata: ResolverMetadata

    suspend fun resolve(
        id: String,
        selections: RawSelectionSet,
        context: EngineExecutionContext
    ): EngineObjectData
}
