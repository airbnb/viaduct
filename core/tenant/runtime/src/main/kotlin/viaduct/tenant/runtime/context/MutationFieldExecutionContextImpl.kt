package viaduct.tenant.runtime.context

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

/**
 * Implementation of [MutationFieldExecutionContext] for mutation field resolvers, extending
 * [BaseFieldExecutionContextImpl] with mutation execution via [mutation].
 */
class MutationFieldExecutionContextImpl<Q : Query, M : Mutation>(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    selections: SelectionSet<CompositeOutput>,
    requestContext: Any?,
    arguments: Arguments,
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
        ownedSelections,
    ) {
    override fun selections(): SelectionSet<CompositeOutput> = selectionSet()

    override fun ownedSelections(): SelectionSet<CompositeOutput> = ownedSelectionSet()

    override suspend fun mutation(
        operation: MutationFromAnnotation,
        variables: Map<String, Any?>
    ): M = mutation(engineExecutionContextWrapper.selectionsForOperation(mutationType(), operation.operationText, variables))

    @Suppress("UNCHECKED_CAST")
    private fun mutationType(): Type<M> = reflectionLoader.reflectionFor(schema.schema.mutationType.name) as Type<M>

    private suspend fun <T : Mutation> mutation(selections: SelectionSet<T>) = engineExecutionContextWrapper.mutation(this, selections)
}
