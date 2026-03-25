package viaduct.api.types

import viaduct.api.connection.OffsetLimit
import viaduct.apiannotations.ExperimentalApi

/**
 * Typed access to the pagination inputs (`first`, `after`, `last`, `before`) of a connection field.
 *
 * Codegen picks the right sub-interface based on which arguments the schema declares.
 * [viaduct.api.internal.ConnectionBuilder] calls [toOffsetLimit] internally — call it directly
 * only when you need the offset/limit to query a backend before using the builder.
 *
 * @see ForwardConnectionArguments
 * @see BackwardConnectionArguments
 * @see MultidirectionalConnectionArguments
 */
@ExperimentalApi
interface ConnectionArguments : Arguments {
    /**
     * Converts connection arguments to offset/limit for database queries.
     *
     * @param defaultPageSize Default number of items when first/last not specified (default: 20)
     * @return OffsetLimit containing the calculated offset and limit
     * @throws IllegalArgumentException for invalid argument combinations or values
     */
    @ExperimentalApi
    fun toOffsetLimit(defaultPageSize: Int = 20): OffsetLimit

    /**
     * Validate connection arguments without converting.
     *
     * @throws IllegalArgumentException if arguments are invalid
     */
    @ExperimentalApi
    fun validate()
}
