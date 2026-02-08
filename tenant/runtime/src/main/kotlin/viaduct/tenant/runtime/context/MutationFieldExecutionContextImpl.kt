package viaduct.tenant.runtime.context

import kotlin.reflect.KClass
import viaduct.api.context.MutationFieldExecutionContext
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
 * Mutation resolvers can access query data via [queryValue] (lazy) or [getQueryValue] (synchronous).
 * The [getQueryValue] method returns a synchronously-accessible version where all selections
 * declared in the resolver's `queryValueFragment` have been eagerly resolved.
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
    queryValue: Q,
    syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)?,
    queryCls: KClass<Q>,
) : MutationFieldExecutionContext<Q, M, Arguments, CompositeOutput>,
    BaseFieldExecutionContextImpl<Q>(
        baseData,
        engineExecutionContextWrapper,
        selections,
        requestContext,
        arguments,
        queryValue,
        syncQueryValueGetter,
        queryCls,
    ) {
    @Suppress("UNCHECKED_CAST")
    override suspend fun mutation(
        selections: String,
        variables: Map<String, Any?>
    ): M {
        val mutationType = reflectionLoader.reflectionFor(schema.schema.mutationType.name) as Type<M>
        return mutation(selectionsFor(mutationType, selections, variables))
    }

    private suspend fun <T : Mutation> mutation(selections: SelectionSet<T>) = engineExecutionContextWrapper.mutation(this, "mutation", selections)
}
