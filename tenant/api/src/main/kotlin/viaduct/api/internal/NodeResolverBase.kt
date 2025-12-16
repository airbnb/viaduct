package viaduct.api.internal

import viaduct.api.types.NodeObject
import viaduct.apiannotations.InternalApi

/**
 * Base interface for node resolver classes
 *
 * @param T the return type of the resolve function
 */
@InternalApi
interface NodeResolverBase<T : NodeObject>
