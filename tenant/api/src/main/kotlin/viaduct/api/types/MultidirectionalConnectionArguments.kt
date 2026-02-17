package viaduct.api.types

import viaduct.api.connection.OffsetLimit
import viaduct.apiannotations.ExperimentalApi

/**
 * Arguments supporting both forward and backward pagination.
 *
 * Combines [ForwardConnectionArguments] and [BackwardConnectionArguments]
 * for connections that support bidirectional traversal.
 *
 * Note: Forward and backward pagination cannot be mixed in the same request.
 */
@ExperimentalApi
interface MultidirectionalConnectionArguments :
    ForwardConnectionArguments, BackwardConnectionArguments {
    /**
     * Converts multidirectional pagination arguments to offset/limit.
     *
     * Uses forward pagination (first/after) if provided, otherwise falls back
     * to backward pagination (last/before).
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
