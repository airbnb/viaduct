package viaduct.api.testing.types

import viaduct.api.types.Mutation

/**
 * Wrapper around a [Mutation] that is used to distinguish between different selections in the context mutation map.
 * Pass in one (or more) of these to ResolverTestBase.runMutationFieldResolver to mock out the mutation
 * results for a specific selection set.
 */
class MutationForSelection(
    val selections: String,
    val mutation: Mutation
) : Mutation by mutation
