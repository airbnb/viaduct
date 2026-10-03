package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata

/**
 * Class that delegates to a data loader to call [FieldResolverExecutor]
 */
interface FieldResolverDispatcher {
    /** The required selection set for the resolver */
    val objectSelectionSet: RequiredSelectionSet?

    /** The query selection set for the resolver **/
    val querySelectionSet: RequiredSelectionSet?

    /** Whether the resolver's result varies based on the requested field selections. */
    val isSelective: Boolean

    /** Explicit variable declarations consumed by required-selection compilation. */
    val variableDefinitions: ResolverVariableDefinitions

    val hasRequiredSelectionSets: Boolean

    /** The metadata associated with this resolver **/
    val resolverMetadata: ResolverMetadata

    suspend fun resolve(
        arguments: Map<String, Any?>,
        objectValueFactory: EngineObjectDataFactory,
        queryValueFactory: EngineObjectDataFactory,
        selections: EngineSelectionSet?,
        context: EngineExecutionContext,
    ): Any?
}
