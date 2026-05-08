package viaduct.api

import viaduct.api.types.Arguments
import viaduct.api.types.Mutation
import viaduct.api.types.Query
import viaduct.apiannotations.TypeInferenceApi

/**
 * Specialized resolver base for mutation field resolvers. Mutations have no parent object, so
 * this interface has no O parameter unlike [FieldResolverBase].
 *
 * Q = query type, M = mutation type, A = arguments type, R = return type.
 *
 * This interface exists to support the testing framework only. Other framework code should depend
 * on [ResolverBase] instead. Generated base classes implement both directly.
 */
@TypeInferenceApi
interface MutationResolverBase<Q : Query, M : Mutation, A : Arguments, R> : ResolverBase<R>
