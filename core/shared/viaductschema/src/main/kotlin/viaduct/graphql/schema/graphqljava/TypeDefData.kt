@file:Suppress("FINITE_BOUNDS_VIOLATION_IN_JAVA") // TypeDefinition has recursive type bound

package viaduct.graphql.schema.graphqljava

import graphql.language.TypeDefinition

/**
 * Holds the graphql-java type definition and its extension definitions.
 * Used as the [SchemaWithData.TypeDef.data] value for GJSchemaRaw-style schemas.
 */
internal data class TypeDefData<out D : TypeDefinition<*>, out E : TypeDefinition<*>>(
    val def: D,
    val extensionDefs: List<E>
)
