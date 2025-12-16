package viaduct.api.internal

import graphql.schema.GraphQLObjectType
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData

/**
 * An EngineObjectData that overrides field values on an existing base EOD.
 *
 * Fields are fetched from the overlay first, falling back to the base EOD if not present.
 * This allows partial updates and delegates all other fields to the base without forcing resolution.
 *
 * This enables the toBuilder() pattern where a GRT can be converted to a builder,
 * some fields overridden, and then built back into a new GRT while preserving unmodified fields from the original GRT.
 */
@InternalApi
class OverlayEngineObjectData(
    private val overlay: EngineObjectData,
    private val base: EngineObjectData
) : EngineObjectData {
    override val graphQLObjectType: GraphQLObjectType = base.graphQLObjectType

    override suspend fun fetch(selection: String): Any? {
        val overlaySelections = overlay.fetchSelections()
        return if (selection in overlaySelections) {
            overlay.fetch(selection)
        } else {
            base.fetch(selection)
        }
    }

    override suspend fun fetchOrNull(selection: String): Any? {
        val overlaySelections = overlay.fetchSelections()
        return if (selection in overlaySelections) {
            overlay.fetchOrNull(selection)
        } else {
            base.fetchOrNull(selection)
        }
    }

    override suspend fun fetchSelections(): Iterable<String> =
        buildSet {
            addAll(overlay.fetchSelections())
            addAll(base.fetchSelections())
        }
}
