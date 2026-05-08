package viaduct.api.types

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
     * Returns true if backward pagination needs the total count to resolve the starting offset.
     *
     * This happens whenever [before] is absent, including the default backward-pagination case
     * where [last] is also absent and the default page size is used. Use [toOffsetLimit(Int, Int)]
     * instead of [toOffsetLimit(Int)].
     */
    @ExperimentalApi
    override fun requiresTotalCountForOffsetLimit(): Boolean = before == null

    /**
     * Converts backward pagination arguments to offset/limit.
     *
     * - [last] determines the page size (defaults to [defaultPageSize]).
     * - [before] cursor encodes the index of the first item on the next page; the offset and
     *   limit are computed to return the last items ending just before that position.
     *   Both are clamped so the window never extends before index 0.
     * - If [before] is absent, a negative offset equal to `-pageSize` is returned, signalling
     *   [viaduct.api.internal.ConnectionBuilder.fromList] to resolve from the tail of the full
     *   list. Prefer using [toOffsetLimit(Int, Int)] with the total count when available.
     */
    @ExperimentalApi
    override fun toOffsetLimit(defaultPageSize: Int): OffsetLimit {
        validate()
        val pageSize = last ?: defaultPageSize
        val beforeOffset = before?.let { OffsetCursor(it).toOffset() }
        if (beforeOffset == null) {
            return OffsetLimit(offset = -pageSize, limit = pageSize)
        }
        val calculatedOffset = maxOf(0, beforeOffset - pageSize)
        val adjustedLimit = minOf(pageSize, beforeOffset)
        return OffsetLimit(offset = calculatedOffset, limit = adjustedLimit)
    }

    /**
     * Converts backward pagination arguments to offset/limit when the total count is known.
     *
     * When [before] is absent, this computes the offset as `max(0, totalCount - last)` to
     * return the last N items from the dataset, avoiding the negative-offset signal used by
     * [toOffsetLimit(Int)].
     *
     * When [before] is present, [totalCount] is ignored and this delegates to [toOffsetLimit(Int)].
     *
     * @param totalCount Total number of items in the full dataset
     * @param defaultPageSize Default number of items when last not specified (default: 20)
     */
    @ExperimentalApi
    override fun toOffsetLimit(
        totalCount: Int,
        defaultPageSize: Int
    ): OffsetLimit {
        validate()
        require(totalCount >= 0) { "totalCount must be non-negative, got: $totalCount" }
        val beforeOffset = before?.let { OffsetCursor(it).toOffset() }
        if (beforeOffset != null) {
            return toOffsetLimit(defaultPageSize)
        }
        val pageSize = last ?: defaultPageSize
        val calculatedOffset = maxOf(0, totalCount - pageSize)
        val adjustedLimit = minOf(pageSize, totalCount)
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
