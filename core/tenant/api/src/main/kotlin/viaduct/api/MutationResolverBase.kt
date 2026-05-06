package viaduct.api

import viaduct.api.types.Arguments
import viaduct.api.types.Mutation
import viaduct.api.types.Query
import viaduct.apiannotations.StableApi

/**
 * Typed base for mutation field resolvers.
 *
 * Note: mutations have no parent object, so this interface has no O parameter.
 * The parameter order is: Q = query type, M = mutation type, A = arguments type, R = return type.
 */
@StableApi
interface MutationResolverBase<Q : Query, M : Mutation, A : Arguments, R> : ResolverBase<R>
