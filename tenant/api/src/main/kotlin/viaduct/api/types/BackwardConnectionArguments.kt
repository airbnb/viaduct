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
}
