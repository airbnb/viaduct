package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi

/**
 * Result of converting [viaduct.api.types.ConnectionArguments] to an offset/limit pair.
 *
 * This is the bridge between GraphQL pagination arguments (`first`, `after`, `last`, `before`)
 * and the offset-based slice that a backend query requires. Produced by
 * [viaduct.api.types.ConnectionArguments.toOffsetLimit] and consumed by
 * [viaduct.api.internal.ConnectionBuilder] internally.
 *
 * @property offset Index of the first item to fetch. A negative value signals
 *   [viaduct.api.internal.ConnectionBuilder.fromList] to resolve from the tail of the dataset
 *  (e.g. `-1` is the last item, `-10` means the 10th from the end).
 * @property limit Maximum number of items to fetch.
 */
@ExperimentalApi
data class OffsetLimit(
    val offset: Int,
    val limit: Int,
)
