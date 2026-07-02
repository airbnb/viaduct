package viaduct.api.context

import viaduct.api.documents.MutationFromAnnotation
import viaduct.api.documents.Selections
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.StableApi

/** An [ExecutionContext] provided to resolvers for root Mutation type fields */
@StableApi
interface MutationFieldExecutionContext<
    Q : Query,
    M : Mutation,
    A : Arguments,
    R : CompositeOutput
> : BaseFieldExecutionContext<Q, A, R> {
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

    /**
     * Loads the operation declared by a
     * [@GraphQLOperation][viaduct.api.documents.GraphQLOperation] mutation object on the root
     * Mutation type, and returns the response typed as [M].
     *
     * Example usage:
     * ```
     * val result = ctx.mutation(SendMessageMutation, mapOf("input" to inputValue))
     * ```
     *
     * @param operation The mutation operation object declaring the operation document
     * @param variables Optional variables to use in the operation
     * @return The mutation result typed as [M]
     */
    @ExperimentalApi
    suspend fun mutation(
        operation: MutationFromAnnotation,
        variables: Map<String, Any?> = emptyMap()
    ): M
}
