package viaduct.api

import viaduct.api.types.Arguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.TypeInferenceApi

/**
 * Specialized resolver base for field resolvers. Exposes type parameters that allow the testing
 * framework to infer parent object, query, and arguments types at compile time.
 *
 * O = parent object type, Q = query type, A = arguments type, R = return type.
 *
 * This interface exists to support the testing framework only. Other framework code should depend
 * on [ResolverBase] instead. Generated base classes implement both directly.
 */
@TypeInferenceApi
interface FieldResolverBase<O : Object, Q : Query, A : Arguments, R> : ResolverBase<R>
