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

/**
 * Internal backing for the public [isPresent][viaduct.api.reflect.isPresent] extension.
 *
 * Kept as a separate `internal` interface (rather than a member of the `@StableApi` [InputLike])
 * so it is invisible to tenants — `isPresent(Field)` is the only presence API on the tenant
 * surface. Living in the `types` module lets the `reflect` module reach it via friend visibility
 * (both are Bazel `associates` of `types`) without `reflect` depending on the `internal` module.
 *
 * Only the map-backed generated inputs (`InputLikeBase`) implement this; argument wrappers without
 * field data (e.g. [Arguments.NoArguments]) do not, so [isPresent][viaduct.api.reflect.isPresent]
 * treats them as absent.
 */
internal interface FieldPresenceProbe {
    fun isFieldPresent(fieldName: String): Boolean
}
