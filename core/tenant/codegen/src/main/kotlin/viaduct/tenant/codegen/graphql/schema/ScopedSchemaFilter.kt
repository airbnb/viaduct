package viaduct.tenant.codegen.graphql.schema

import viaduct.graphql.schema.SchemaFilter
import viaduct.graphql.schema.ViaductSchema

/**
 * Filters the schema to just the elements that are in at least one of the given scopes.
 *
 * This assumes the directive argument values are graphql.language.Value types, which is the case
 * when constructing a GJSchemaRaw or GJSchema with the default ValueConverter.
 */
class ScopedSchemaFilter(private val appliedScopes: Set<String>) : SchemaFilter {
    init {
        if (appliedScopes.isEmpty()) {
            throw IllegalArgumentException("There must be at least one scope provided to ScopedSchemaFilter")
        }
    }

    override fun includeTypeDef(typeDef: ViaductSchema.TypeDef): Boolean {
        return appliedScopes.any { typeDef.isInScope(it) }
    }

    override fun includeField(field: ViaductSchema.Field): Boolean {
        return appliedScopes.any { field.isInScope(it) }
    }

    override fun includeEnumValue(enumValue: ViaductSchema.EnumValue): Boolean {
        return appliedScopes.any { enumValue.isInScope(it) }
    }

    override fun includeSuper(
        record: ViaductSchema.OutputRecord,
        superInterface: ViaductSchema.Interface
    ): Boolean {
        val ext = record.extensions.first { it.supers.any { it.name == superInterface.name } }
        val extensionScopes = ext.scopes?.toSet() ?: return false
        if ("*" in extensionScopes) return true
        return appliedScopes.any { it in extensionScopes && superInterface.isInScope(it) }
    }
}
