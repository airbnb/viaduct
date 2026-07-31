package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi
import viaduct.tenant.runtime.support.ConnectionArgumentsSupport

/**
 * Arguments for forward pagination through a connection.
 *
 * The offset/limit math and validation are delegated to the language-neutral
 * [ConnectionArgumentsSupport] so the Kotlin and Java Tenant APIs stay in lockstep.
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
     * - [first] determines the page size (defaults to [defaultPageSize]).
     * - [after] cursor encodes the index of the last-seen item; this decodes it and adds 1 so the
     *   returned offset points to the first item *after* that cursor.
     * - If [after] is absent, pagination starts from offset 0.
     */
    @ExperimentalApi
    override fun toOffsetLimit(defaultPageSize: Int): OffsetLimit =
        ConnectionArgumentsSupport.forwardOffsetLimit(first, after, defaultPageSize).let {
            OffsetLimit(offset = it.offset, limit = it.limit)
        }

    /**
     * Validates forward pagination arguments.
     *
     * @throws IllegalArgumentException if first is not positive or after cursor is invalid
     */
    @ExperimentalApi
    override fun validate() = ConnectionArgumentsSupport.validateForward(first, after)
}
