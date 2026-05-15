package viaduct.service.api

import viaduct.apiannotations.StableApi

/**
 * Identifies which schema variant to use when executing a GraphQL operation.
 *
 * Viaduct supports multiple schema variants for a single service:
 * - [Full] — the default, complete schema containing all types and fields.
 * - A named scoped variant — a subset of the full schema restricted by scope IDs declared via
 *   [viaduct.service.runtime.SchemaConfiguration]. Construct with `SchemaId("myScope")`.
 * - [None] — represents a non-existent schema, used as a sentinel value.
 *
 * Equality and hash code are based on [id] alone, so `SchemaId("FULL") == SchemaId.Full`
 * and either is a valid lookup key for the same schema.
 *
 * @see viaduct.service.ViaductBuilder.withSchemaConfiguration
 */
@StableApi
class SchemaId(val id: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SchemaId) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "SchemaId(id='$id')"

    companion object {
        /** A schema ID that represents the full schema without any scoping. */
        @JvmField
        val Full = SchemaId("FULL")

        /** A schema ID representing a non-existent schema (sentinel). */
        @JvmField
        val None = SchemaId("NONE")
    }
}
