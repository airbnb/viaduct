package viaduct.api.types

import viaduct.api.connection.OffsetCursor
import viaduct.api.connection.OffsetLimit
import viaduct.apiannotations.ExperimentalApi

/**
 * Arguments for backward pagination through a connection.
 *
 * @property last Maximum number of items to return from the end.
 * @property before Cursor to start fetching items before (exclusive).
 * @see ForwardConnectionArguments
 */
@ExperimentalApi
interface BackwardConnectionArguments : ConnectionArguments {
    val last: Int?
    val before: String?

    /**
     * Converts backward pagination arguments to offset/limit.
     *
     * - `last` determines the page size (defaults to [defaultPageSize]).
     * - `before` cursor encodes the index of the first item on the next page; the offset and
     *   limit are computed to return the `last` items ending just before that position.
     *   Both are clamped so the window never extends before index 0.
     * - If `before` is absent, [OffsetLimit.backwards] is set to `true` and offset is 0,
     *   signalling [viaduct.api.internal.ConnectionBuilder.fromList] to take from the tail of
     *   the full list rather than the head.
     */
    @ExperimentalApi
    override fun toOffsetLimit(defaultPageSize: Int): OffsetLimit {
        validate()
        val pageSize = last ?: defaultPageSize
        val beforeOffset = before?.let { OffsetCursor(it).toOffset() }
        if (beforeOffset == null) {
            return OffsetLimit(offset = 0, limit = pageSize, backwards = true)
        }
        val calculatedOffset = maxOf(0, beforeOffset - pageSize)
        val adjustedLimit = minOf(pageSize, beforeOffset)
        return OffsetLimit(offset = calculatedOffset, limit = adjustedLimit)
    }

    /**
     * Validates backward pagination arguments.
     *
     * @throws IllegalArgumentException if last is not positive or before cursor is invalid
     */
    @ExperimentalApi
    override fun validate() {
        last?.let { require(it > 0) { "last must be positive, got: $it" } }
        before?.let { require(OffsetCursor.isValid(it)) { "Invalid before cursor: $it" } }
    }
}
