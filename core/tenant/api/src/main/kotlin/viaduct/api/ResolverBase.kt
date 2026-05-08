package viaduct.api

import viaduct.apiannotations.TypeInferenceApi

/**
 * Base interface for field resolver classes.
 *
 * @param T the return type of the resolve function
 */
@TypeInferenceApi
interface ResolverBase<T>
