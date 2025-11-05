package viaduct.engine.api

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
