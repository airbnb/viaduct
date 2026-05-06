package viaduct.api

import viaduct.api.types.Arguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.StableApi

/**
 * Typed base for field resolvers.
 *
 * O = parent object type, Q = query type, A = arguments type, R = return type.
 *
 * The generated abstract class for each @resolver field extends this instead of ResolverBase<R>,
 * allowing the test framework to infer O, Q, A at compile time.
 */
@StableApi
interface FieldResolverBase<O : Object, Q : Query, A : Arguments, R> : ResolverBase<R>
