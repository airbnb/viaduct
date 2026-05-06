package viaduct.api

import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.StableApi

/**
 * Typed base for connection field resolvers. Extends [FieldResolverBase] with the additional
 * constraint that A is a [ConnectionArguments], exposing pagination utilities (toOffsetLimit, etc.).
 *
 * R's upper bound is `Connection<*, *>?` so the generator can emit nullable connection return
 * types (e.g. `FooConnection?`) without tripping the bound check.
 *
 * Because this extends FieldResolverBase, the runFieldResolver(resolver, block) typed overload
 * in the test framework covers connection resolvers without a separate test method.
 */
@StableApi
interface ConnectionResolverBase<O : Object, Q : Query, A : ConnectionArguments, R : Connection<*, *>?> :
    FieldResolverBase<O, Q, A, R>
