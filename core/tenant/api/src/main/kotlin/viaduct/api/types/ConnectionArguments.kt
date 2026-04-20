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
     * Returns true if [toOffsetLimit] requires knowing the total count of items to compute
     * the correct offset.
     *
     * This is the case for backward pagination when only [viaduct.api.types.BackwardConnectionArguments.last]
     * is specified (no [viaduct.api.types.BackwardConnectionArguments.before] cursor),
     * because the offset must be computed as `totalCount - last`.
     *
     * When this returns true, use [toOffsetLimit(Int, Int)] with the total count instead of
     * the single-argument overload.
     */
    @ExperimentalApi
    fun requiresTotalCountForOffsetLimit(): Boolean = false

    /**
     * Converts connection arguments to offset/limit for database queries.
     *
     * @param [defaultPageSize] Default number of items when first/last not specified (default: 20)
     * @return [OffsetLimit] containing the calculated offset and limit
     * @throws IllegalArgumentException for invalid argument combinations or values
     */
    @ExperimentalApi
    fun toOffsetLimit(defaultPageSize: Int = 20): OffsetLimit

    /**
     * Converts connection arguments to offset/limit when the total count of items is known.
     *
     * This overload is required when [requiresTotalCountForOffsetLimit] returns true (i.e.,
     * backward pagination with only `last` specified). In that case, the offset is computed
     * as `max(0, totalCount - last)`.
     *
     * For forward pagination or backward pagination with a `before` cursor, the [totalCount]
     * is ignored and this delegates to [toOffsetLimit(Int)].
     *
     * @param totalCount Total number of items in the full dataset
     * @param defaultPageSize Default number of items when first/last not specified (default: 20)
     * @return OffsetLimit containing the calculated offset and limit
     * @throws IllegalArgumentException for invalid argument combinations or values
     */
    @ExperimentalApi
    fun toOffsetLimit(
        totalCount: Int,
        defaultPageSize: Int = 20
    ): OffsetLimit {
        require(totalCount >= 0) { "totalCount must be non-negative, got: $totalCount" }
        return toOffsetLimit(defaultPageSize)
    }

    /**
     * Validate connection arguments without converting.
     *
     * @throws IllegalArgumentException if arguments are invalid
     */
    @ExperimentalApi
    fun validate()
}
