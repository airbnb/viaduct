package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi
import viaduct.tenant.runtime.support.ConnectionArgumentsSupport

/**
 * Arguments for connections that expose all four pagination args (`[first]`, `[after]`, `[last]`, `[before]`).
 *
 * Forward args take precedence over backward in [toOffsetLimit]; passing neither returns the first
 * page at the default size. Forward and backward args cannot be mixed in the same request.
 *
 * The math and validation are delegated to the language-neutral [ConnectionArgumentsSupport], which
 * validates exactly once per call (the previous inheritance-based implementation validated twice).
 *
 * Prefer [ForwardConnectionArguments] or [BackwardConnectionArguments] when only one direction
 * is needed.
 */
@ExperimentalApi
interface MultidirectionalConnectionArguments :
    ForwardConnectionArguments, BackwardConnectionArguments {
    /**
     * Returns true if backward pagination is active ([last]/[before]) and requires total count.
     *
     * Forward pagination never requires total count. Backward pagination requires it only when the
     * [before] cursor is absent.
     */
    @ExperimentalApi
    override fun requiresTotalCountForOffsetLimit(): Boolean = ConnectionArgumentsSupport.multidirectionalRequiresTotalCount(first, after, last, before)

    /**
     * Converts multidirectional pagination arguments to offset/limit.
     *
     * Uses forward pagination ([first]/[after]) if provided, otherwise falls back to backward
     * pagination ([last]/[before]).
     */
    @ExperimentalApi
    override fun toOffsetLimit(defaultPageSize: Int): OffsetLimit =
        ConnectionArgumentsSupport.multidirectionalOffsetLimit(first, after, last, before, defaultPageSize).let {
            OffsetLimit(offset = it.offset, limit = it.limit)
        }

    /**
     * Converts multidirectional pagination arguments to offset/limit when total count is known.
     *
     * Uses forward pagination ([first]/[after]) if provided, otherwise falls back to backward
     * pagination ([last]/[before]).
     */
    @ExperimentalApi
    override fun toOffsetLimit(
        totalCount: Int,
        defaultPageSize: Int
    ): OffsetLimit =
        ConnectionArgumentsSupport.multidirectionalOffsetLimit(first, after, last, before, totalCount, defaultPageSize).let {
            OffsetLimit(offset = it.offset, limit = it.limit)
        }

    /**
     * Validates multidirectional pagination arguments.
     *
     * @throws IllegalArgumentException if mixing forward and backward pagination, or if individual
     *     arguments are invalid
     */
    @ExperimentalApi
    override fun validate() = ConnectionArgumentsSupport.validateMultidirectional(first, after, last, before)
}
