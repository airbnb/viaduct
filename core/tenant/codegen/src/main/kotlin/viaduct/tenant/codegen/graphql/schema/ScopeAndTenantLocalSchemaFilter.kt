package viaduct.tenant.codegen.graphql.schema

import viaduct.graphql.schema.SchemaFilter
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaFactory

/**
 * Filters the schema for scoped and base-schema codegen.
 *
 * Scoped mode keeps elements that are in at least one of the given scopes. Base-schema mode skips scope filtering.
 * Both modes filter tenant-local fields.
 *
 * This assumes the directive argument values are graphql.language.Value types, which is the case
 * when constructing a GJSchemaRaw or GJSchema with the default ValueConverter.
 */
class ScopeAndTenantLocalSchemaFilter private constructor(
    private val appliedScopes: Set<String>,
    private val filterByScopes: Boolean,
) : SchemaFilter {
    companion object {
        private val TENANT_LOCAL_DIRECTIVE_NAME = DefaultSchemaFactory.DefaultDirective.TENANT_LOCAL.directiveName

        fun baseSchema(): ScopeAndTenantLocalSchemaFilter = ScopeAndTenantLocalSchemaFilter(emptySet(), filterByScopes = false)
    }

    constructor(appliedScopes: Set<String>) : this(appliedScopes, filterByScopes = true) {
        if (appliedScopes.isEmpty()) {
            throw IllegalArgumentException("There must be at least one scope provided to ScopeAndTenantLocalSchemaFilter")
        }
    }

    override fun includeTypeDef(typeDef: ViaductSchema.TypeDef): Boolean {
        return !filterByScopes || appliedScopes.any { typeDef.isInScope(it) }
    }

    override fun includeField(field: ViaductSchema.Field): Boolean {
        return !field.hasAppliedDirective(TENANT_LOCAL_DIRECTIVE_NAME) &&
            (!filterByScopes || appliedScopes.any { field.isInScope(it) })
    }

    override fun includeEnumValue(enumValue: ViaductSchema.EnumValue): Boolean {
        return !filterByScopes || appliedScopes.any { enumValue.isInScope(it) }
    }

    override fun includeSuper(
        record: ViaductSchema.OutputRecord,
        superInterface: ViaductSchema.Interface
    ): Boolean {
        if (!filterByScopes) {
            return true
        }
        val ext = record.extensions.first { it.supers.any { it.name == superInterface.name } }
        val extensionScopes = ext.scopes?.toSet() ?: return false
        if ("*" in extensionScopes) return true
        return appliedScopes.any { it in extensionScopes && superInterface.isInScope(it) }
    }
}
