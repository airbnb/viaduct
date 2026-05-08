package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi

/**
 * Arguments for connections that expose all four pagination args (`[first]`, `[after]`, `[last]`, `[before]`).
 *
 * Forward args take precedence over backward in [toOffsetLimit]; passing neither returns the first
 * page at the default size. Forward and backward args cannot be mixed in the same request.
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
     * Forward pagination never requires total count. Backward pagination requires it only
     * when [before] cursor is absent.
     */
    @ExperimentalApi
    override fun requiresTotalCountForOffsetLimit(): Boolean {
        return when {
            first != null || after != null -> false
            // When backward pagination is active, need total count only when before is absent
            last != null || before != null -> before == null
            else -> false
        }
    }

    /**
     * Converts multidirectional pagination arguments to offset/limit.
     *
     * Uses forward pagination ([first]/[after]) if provided, otherwise falls back
     * to backward pagination ([last]/[before]).
     */
    @ExperimentalApi
    override fun toOffsetLimit(defaultPageSize: Int): OffsetLimit {
        validate()

        return when {
            first != null || after != null -> super<ForwardConnectionArguments>.toOffsetLimit(defaultPageSize)
            last != null || before != null -> super<BackwardConnectionArguments>.toOffsetLimit(defaultPageSize)
            else -> OffsetLimit(offset = 0, limit = defaultPageSize)
        }
    }

    /**
     * Converts multidirectional pagination arguments to offset/limit when total count is known.
     *
     * Uses forward pagination ([first]/[after]) if provided, otherwise falls back
     * to backward pagination ([last]/[before]).
     */
    @ExperimentalApi
    override fun toOffsetLimit(
        totalCount: Int,
        defaultPageSize: Int
    ): OffsetLimit {
        validate()
        require(totalCount >= 0) { "totalCount must be non-negative, got: $totalCount" }

        return when {
            first != null || after != null -> super<ForwardConnectionArguments>.toOffsetLimit(defaultPageSize)
            last != null || before != null -> super<BackwardConnectionArguments>.toOffsetLimit(totalCount, defaultPageSize)
            else -> OffsetLimit(offset = 0, limit = defaultPageSize)
        }
    }

    /**
     * Validates multidirectional pagination arguments.
     *
     * @throws IllegalArgumentException if mixing forward and backward pagination,
     *         or if individual arguments are invalid
     */
    @ExperimentalApi
    override fun validate() {
        // Validate individual args using parent implementations
        super<ForwardConnectionArguments>.validate()
        super<BackwardConnectionArguments>.validate()

        // Cannot mix forward/backward
        if ((first != null || after != null) && (last != null || before != null)) {
            throw IllegalArgumentException(
                "Cannot mix forward (first/after) and backward (last/before) pagination"
            )
        }
    }
}
