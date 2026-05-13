package viaduct.api.types

import viaduct.apiannotations.StableApi

/**
 * Tagging interface for GraphQL interface types.
 *
 * Codegen-produced Kotlin interfaces that correspond to GraphQL `interface` types implement
 * this. Like [Object], they are composite (their fields can be selected), but they can be
 * implemented by multiple concrete types.
 */
@StableApi
interface Interface : CompositeOutput
