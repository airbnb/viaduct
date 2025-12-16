package viaduct.api.internal

import viaduct.apiannotations.InternalApi

/**
 * Base interface for field resolver classes
 *
 * @param T the return type of the resolve function
 */
@InternalApi
interface ResolverBase<T>
