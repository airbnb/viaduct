package viaduct.api.context

import viaduct.api.select.Selections
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Query
import viaduct.apiannotations.StableApi

/** An [ExecutionContext] provided to resolvers for root Mutation type fields */
@StableApi
interface MutationFieldExecutionContext<
    Q : Query,
    M : Mutation,
    A : Arguments,
    O : CompositeOutput
> : BaseFieldExecutionContext<Q, A, O> {
    /**
     * Loads the provided selections on the root Mutation type, and returns the response typed as [M].
     * This is a convenience method that combines [selectionsFor] and [mutation].
     *
     * Example usage:
     * ```
     * val result = ctx.mutation("{ createUser(input: $input) { id name } }")
     * ```
     *
     * @param selections The selections to load on the root Mutation type
     * @param variables Optional variables to use in the selections
     * @return The mutation result typed as [M]
     */
    suspend fun mutation(
        selections: @Selections String,
        variables: Map<String, Any?> = emptyMap()
    ): M
}
