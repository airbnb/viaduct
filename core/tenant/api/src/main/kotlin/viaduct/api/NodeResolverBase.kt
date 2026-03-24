package viaduct.api

import viaduct.api.types.NodeObject
import viaduct.apiannotations.StableApi

/**
 * Base interface for node resolver classes
 *
 * @param T the return type of the resolve function
 */
@StableApi
interface NodeResolverBase<T : NodeObject>
