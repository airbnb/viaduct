package viaduct.api

import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.InternalApi

/**
 * Specialized resolver base for connection field resolvers. Extends [FieldResolverBase] with the
 * additional constraint that A is a [ConnectionArguments], exposing pagination utilities.
 *
 * R's upper bound is `Connection<*, *>?` so the generator can emit nullable connection return
 * types (e.g. `FooConnection?`) without tripping the bound check.
 *
 * This interface exists to support the testing framework only. Other framework code should depend
 * on [ResolverBase] instead. Generated base classes implement both directly.
 */
@InternalApi
interface ConnectionResolverBase<O : Object, Q : Query, A : ConnectionArguments, R : Connection<*, *>?> :
    FieldResolverBase<O, Q, A, R>
