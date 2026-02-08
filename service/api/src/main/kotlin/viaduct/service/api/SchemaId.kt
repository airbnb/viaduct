package viaduct.service.api

import viaduct.apiannotations.StableApi

/**
 * Represents a unique identifier for a schema.
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
