package viaduct.api.connection

import viaduct.apiannotations.ExperimentalApi

/**
 * Result of converting ConnectionArguments to offset/limit.
 *
 * @property offset Zero-based starting position
 * @property limit Maximum number of items to fetch
 * @property backwards If true, the offset should be resolved relative to the end of the list.
 *   This is set when navigating backward without a `before` cursor, meaning "give me the last N items."
 */
@ExperimentalApi
data class OffsetLimit(
    val offset: Int,
    val limit: Int,
    val backwards: Boolean = false
)
