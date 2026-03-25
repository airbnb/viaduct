package viaduct.api.context

import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi

/**
 * A [FieldExecutionContext] for connection fields, with typed access to [ConnectionArguments].
 *
 * Resolvers typically don't interact with the arguments directly — [viaduct.api.internal.ConnectionBuilder]
 * reads them internally when building edges and `pageInfo`. Call `ctx.arguments.toOffsetLimit()`
 * directly only when you need the offset/limit to pass to a backend before using the builder.
 *
 * @param O The parent object type containing this connection field.
 * @param Q The query type for data fetching.
 * @param A The connection arguments type (`first`, `after`, `last`, `before`).
 * @param R The connection output type.
 * @see FieldExecutionContext
 * @see viaduct.api.internal.ConnectionBuilder
 */

@ExperimentalApi
interface ConnectionFieldExecutionContext<
    O : Object,
    Q : Query,
    A : ConnectionArguments,
    R : Connection<*, *>,
> : FieldExecutionContext<O, Q, A, R>
