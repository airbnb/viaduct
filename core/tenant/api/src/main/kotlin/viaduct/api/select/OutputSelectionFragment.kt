package viaduct.api.select

import viaduct.apiannotations.StableApi

/**
 * A selection set rendered as a GraphQL fragment.
 *
 * @property name The name of the rendered GraphQL fragment.
 * @property document The complete GraphQL fragment definition.
 * @property variables Runtime variable bindings available to [document].
 */
@StableApi
data class OutputSelectionFragment(
    val name: String,
    val document: String,
    val variables: Map<String, Any?>,
)
