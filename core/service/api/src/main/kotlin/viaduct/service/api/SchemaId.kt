package viaduct.service.api

import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi

/**
 * Identifies which schema variant to use when executing a GraphQL operation.
 *
 * Viaduct supports multiple schema variants for a single service:
 * - [Base] — the default, unscoped external schema.
 * - [Scoped] — a subset of the base schema restricted by a set of scope IDs,
 *   useful for multi-tenancy or permission-based field visibility.
 * - [None] — represents a non-existent schema, used as a sentinel value.
 *
 * @see viaduct.service.ViaductBuilder.withScopedSchemas
 */
@StableApi
abstract class SchemaId(
    open val id: String
) {
    /**
     * A schema ID that is scoped to a set of scope IDs.
     * @param id The schema ID.
     * @param scopeIds The set of scope IDs the schema is scoped to.
     */
    @InternalApi
    data class Scoped(
        override val id: String,
        val scopeIds: Set<String>
    ) : SchemaId(id)

    /**
     * A schema ID that represents the unscoped external schema.
     */
    @StableApi
    object Base : SchemaId("BASE")

    /**
     * Represents a non-existent schema.
     */
    @StableApi
    object None : SchemaId("NONE")

    override fun toString(): String = "SchemaId(id='$id')"
}
