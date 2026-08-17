package viaduct.tenant.runtime.context

import kotlin.reflect.KClass
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.ResolverOwnedSelectionsContext
import viaduct.api.context.SelectiveFieldExecutionContext
import viaduct.api.documents.MutationFromAnnotation
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Query
import viaduct.engine.api.EngineObjectData

/**
 * Implementation of [MutationFieldExecutionContext] for mutation field resolvers.
 *
 * This class extends [BaseFieldExecutionContextImpl] to add mutation-specific functionality,
 * including access to the mutation object via [mutation].
 *
 * Mutation resolvers can access query data via [getQueryValue], which returns a synchronously-accessible
 * version where all selections declared in the resolver's `queryValueFragment` have been eagerly resolved.
 *
 * @param syncQueryValueGetter A suspending function that returns the synchronous query value,
 *        or null if no query selections were declared by the resolver
 */
class MutationFieldExecutionContextImpl<Q : Query, M : Mutation>(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<CompositeOutput>,
    requestContext: Any?,
    arguments: Arguments,
    syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)?,
    queryCls: KClass<Q>,
    ownedSelections: Lazy<SelectionSet<CompositeOutput>> = lazyOf(selections),
) : MutationFieldExecutionContext<Q, M, Arguments, CompositeOutput>,
    SelectiveFieldExecutionContext<CompositeOutput>,
    ResolverOwnedSelectionsContext<CompositeOutput>,
    BaseFieldExecutionContextImpl<Q, Arguments, CompositeOutput>(
        baseData,
        engineExecutionContextWrapper,
        selections,
        requestContext,
        arguments,
        syncQueryValueGetter,
        queryCls,
        ownedSelections,
    ) {
    override fun selections(): SelectionSet<CompositeOutput> = selectionSet()

    override fun ownedSelections(): SelectionSet<CompositeOutput> = ownedSelectionSet()

    @Deprecated("This API is not supported and will be deleted. Use the GraphQLOperation-based mutation(operation, variables) instead.")
    override suspend fun mutation(
        selections: String,
        variables: Map<String, Any?>
    ): M = mutation(selectionsFor(mutationType(), selections, variables))

    override suspend fun mutation(
        operation: MutationFromAnnotation,
        variables: Map<String, Any?>
    ): M = mutation(engineExecutionContextWrapper.selectionsForOperation(mutationType(), operation.operationText, variables))

    @Suppress("UNCHECKED_CAST")
    private fun mutationType(): Type<M> = reflectionLoader.reflectionFor(schema.schema.mutationType.name) as Type<M>

    private suspend fun <T : Mutation> mutation(selections: SelectionSet<T>) = engineExecutionContextWrapper.mutation(this, selections)
}
