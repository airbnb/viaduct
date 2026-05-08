package viaduct.api

import viaduct.api.types.NodeObject
import viaduct.apiannotations.TypeInferenceApi

/**
 * Specialized resolver base for node resolvers.
 *
 * @param T the node object type returned by the resolver
 *
 * This interface exists to support the testing framework only. Other framework code should depend
 * on [ResolverBase] instead. Generated base classes implement both directly.
 */
@TypeInferenceApi
interface NodeResolverBase<T : NodeObject> : ResolverBase<T>
