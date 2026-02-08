package viaduct.api.types

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
}
