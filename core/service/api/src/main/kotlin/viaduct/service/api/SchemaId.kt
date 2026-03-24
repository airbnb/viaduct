package viaduct.service.api

import viaduct.apiannotations.StableApi

/**
 * Identifies which schema variant to use when executing a GraphQL operation.
 *
 * Viaduct supports multiple schema variants for a single service:
 * - [Full] — the default, complete schema containing all types and fields.
 * - [Scoped] — a subset of the full schema restricted by a set of scope IDs,
 *   useful for multi-tenancy or permission-based field visibility.
 * - [None] — represents a non-existent schema, used as a sentinel value.
 *
 * @see viaduct.service.ViaductBuilder.withSchemaConfiguration
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
    @StableApi
    data class Scoped(
        override val id: String,
        val scopeIds: Set<String>
    ) : SchemaId(id)

    /**
     * A schema ID that represents a full schema without any scoping.
     */
    @StableApi
    object Full : SchemaId("FULL")

    /**
     * Represents a non-existent schema.
     */
    @StableApi
    object None : SchemaId("NONE")

    override fun toString(): String = "SchemaId(id='$id')"
}
