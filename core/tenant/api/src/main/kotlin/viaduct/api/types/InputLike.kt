package viaduct.api.types

import viaduct.apiannotations.StableApi

/**
 * Common supertype for [Input] and [Arguments].
 *
 * Both GraphQL input object types and the virtual argument-wrapper types generated for field
 * arguments need to be treated the same way by the mapping layer. This interface exists solely
 * to provide that common root; application code should use [Input] or [Arguments] directly.
 */
@StableApi
interface InputLike : GRT
