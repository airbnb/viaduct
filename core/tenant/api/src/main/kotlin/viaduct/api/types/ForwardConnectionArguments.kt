package viaduct.api.types

import viaduct.api.connection.OffsetCursor
import viaduct.api.connection.OffsetLimit
import viaduct.apiannotations.ExperimentalApi

/**
 * Arguments for forward pagination through a connection.
 *
 * @property first Maximum number of items to return from the beginning.
 * @property after Cursor to start fetching items after (exclusive).
 * @see BackwardConnectionArguments
 */
@ExperimentalApi
interface ForwardConnectionArguments : ConnectionArguments {
    val first: Int?
    val after: String?

    /**
     * Converts forward pagination arguments to offset/limit.
     *
     * - `first` determines the page size (defaults to [defaultPageSize])
     * - `after` cursor is decoded to determine the starting offset
     */
    @ExperimentalApi
    override fun toOffsetLimit(defaultPageSize: Int): OffsetLimit {
        validate()
        val afterOffset = after?.let { OffsetCursor(it).toOffset() + 1 } ?: 0
        val pageSize = first ?: defaultPageSize
        return OffsetLimit(offset = afterOffset, limit = pageSize)
    }

    /**
     * Validates forward pagination arguments.
     *
     * @throws IllegalArgumentException if first is not positive or after cursor is invalid
     */
    @ExperimentalApi
    override fun validate() {
        first?.let { require(it > 0) { "first must be positive, got: $it" } }
        after?.let { require(OffsetCursor.isValid(it)) { "Invalid after cursor: $it" } }
    }
}
