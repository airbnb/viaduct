package viaduct.api.types

import viaduct.apiannotations.StableApi

/**
 * Tagging interface for GraphQL input object types.
 *
 * Codegen-produced Kotlin classes for `input` types in the schema implement this interface.
 * They are used as structured argument values passed into resolver methods.
 */
@StableApi
interface Input : InputLike
