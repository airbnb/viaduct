package viaduct.api.context

import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi

/**
 * Execution context for resolving connection fields with pagination support.
 *
 * Extends [FieldExecutionContext] to provide specialized handling for
 * GraphQL Relay-style connection fields.
 *
 * @param T The parent object type containing this connection field.
 * @param Q The query type for data fetching.
 * @param A The connection arguments type for pagination parameters.
 * @param O The connection output type.
 * @see FieldExecutionContext
 * @see Connection
 * @see ConnectionArguments
 */

@ExperimentalApi
interface ConnectionFieldExecutionContext<
    T : Object,
    Q : Query,
    A : ConnectionArguments,
    O : Connection<*, *>,
> : FieldExecutionContext<T, Q, A, O>
