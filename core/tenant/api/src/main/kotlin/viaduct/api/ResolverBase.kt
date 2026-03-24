package viaduct.api

import viaduct.apiannotations.StableApi

/**
 * Base interface for field resolver classes
 *
 * @param T the return type of the resolve function
 */
@StableApi
interface ResolverBase<T>
