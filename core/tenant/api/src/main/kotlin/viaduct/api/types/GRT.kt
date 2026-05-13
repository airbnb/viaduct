package viaduct.api.types

import viaduct.apiannotations.StableApi

/**
 * Base interface for all GraphQL Representational Types (GRTs).
 *
 * GRTs are the Kotlin representations of GraphQL schema types. Every codegen-produced class
 * (object types, input types, enums, arguments wrappers, etc.) implements this interface, which
 * lets the framework handle them generically. Application developers never implement this directly;
 * they interact with the more specific sub-interfaces such as [Object], [Input], or [Enum].
 */
@StableApi
interface GRT
