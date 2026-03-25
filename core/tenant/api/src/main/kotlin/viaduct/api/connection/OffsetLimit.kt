package viaduct.api.connection

import viaduct.apiannotations.ExperimentalApi

/**
 * Result of converting [viaduct.api.types.ConnectionArguments] to an offset/limit pair.
 *
 * This is the bridge between GraphQL pagination arguments (`first`, `after`, `last`, `before`)
 * and the offset-based slice that a backend query requires. Produced by
 * [viaduct.api.types.ConnectionArguments.toOffsetLimit] and consumed by
 * [viaduct.api.internal.ConnectionBuilder] internally.
 *
 * @property offset Zero-based index of the first item to fetch.
 * @property limit Maximum number of items to fetch.
 * @property backwards If true, the requested window should be resolved from the end of the
 *   full dataset. Set when `last` is provided without a `before` cursor, meaning
 *   "give me the last N items." [viaduct.api.internal.ConnectionBuilder.fromList] uses
 *   this flag to slice from the tail; [viaduct.api.internal.ConnectionBuilder.fromSlice]
 *   ignores it (the caller is responsible for pre-computing the correct slice).
 */
@ExperimentalApi
data class OffsetLimit(
    val offset: Int,
    val limit: Int,
    val backwards: Boolean = false
)
