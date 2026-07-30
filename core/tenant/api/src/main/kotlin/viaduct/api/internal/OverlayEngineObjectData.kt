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
    private val overlay: EngineObjectData.Sync,
    private val base: EngineObjectData.Sync
) : EngineObjectData.Sync {
    override val type: GraphQLObjectType = base.type

    override fun get(selection: String): Any? = if (overlay.isPresent(selection)) overlay.get(selection) else base.get(selection)

    override fun getOrNull(selection: String): Any? = if (overlay.isPresent(selection)) overlay.getOrNull(selection) else base.getOrNull(selection)

    override fun isPresent(selection: String): Boolean = overlay.isPresent(selection) || base.isPresent(selection)

    override fun getSelections(): Iterable<String> =
        buildSet {
            addAll(overlay.getSelections())
            addAll(base.getSelections())
        }

    override suspend fun fetch(selection: String) = get(selection)

    override suspend fun fetchOrNull(selection: String) = getOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = getSelections()
}
