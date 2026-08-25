package viaduct.tenant.codegen.graphql.schema

import viaduct.graphql.schema.SchemaFilter
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaFactory

/**
 * Filters the schema for scoped and base-schema codegen.
 *
 * Scoped mode keeps elements that are in at least one of the given scopes. Base-schema mode skips scope filtering.
 * Both modes filter tenant-local fields, parent fields, and BackingData fields. They can also filter the
 * `@bypassPolicyCheck` directive definition.
 *
 * This assumes the directive argument values are graphql.language.Value types, which is the case
 * when constructing a GJSchemaRaw or GJSchema with the default ValueConverter.
 */
class ScopeAndTenantLocalSchemaFilter private constructor(
    private val appliedScopes: Set<String>,
    private val filterByScopes: Boolean,
    private val filterBypassPolicyCheckDirective: Boolean,
) : SchemaFilter {
    companion object {
        private const val BACKING_DATA_SCALAR_NAME = "BackingData"
        private const val BYPASS_POLICY_CHECK_DIRECTIVE_NAME = "bypassPolicyCheck"
        private val PARENT_DIRECTIVE_NAME = DefaultSchemaFactory.DefaultDirective.PARENT.directiveName
        private val TENANT_LOCAL_DIRECTIVE_NAME = DefaultSchemaFactory.DefaultDirective.TENANT_LOCAL.directiveName

        fun baseSchema(): ScopeAndTenantLocalSchemaFilter = baseSchema(filterBypassPolicyCheckDirective = false)

        fun baseSchema(filterBypassPolicyCheckDirective: Boolean): ScopeAndTenantLocalSchemaFilter =
            ScopeAndTenantLocalSchemaFilter(
                emptySet(),
                filterByScopes = false,
                filterBypassPolicyCheckDirective = filterBypassPolicyCheckDirective,
            )
    }

    constructor(appliedScopes: Set<String>) : this(appliedScopes, filterBypassPolicyCheckDirective = false)

    constructor(
        appliedScopes: Set<String>,
        filterBypassPolicyCheckDirective: Boolean,
    ) : this(
        appliedScopes,
        filterByScopes = true,
        filterBypassPolicyCheckDirective = filterBypassPolicyCheckDirective,
    ) {
        if (appliedScopes.isEmpty()) {
            throw IllegalArgumentException("There must be at least one scope provided to ScopeAndTenantLocalSchemaFilter")
        }
    }

    override fun includeTypeDef(typeDef: ViaductSchema.TypeDef): Boolean {
        return !filterByScopes || appliedScopes.any { typeDef.isInScope(it) }
    }

    override fun includeField(field: ViaductSchema.Field): Boolean {
        return !field.isTenantLocalEquivalentField() &&
            (!filterByScopes || appliedScopes.any { field.isInScope(it) })
    }

    override fun includeEnumValue(enumValue: ViaductSchema.EnumValue): Boolean {
        return !filterByScopes || appliedScopes.any { enumValue.isInScope(it) }
    }

    override fun includeDirective(directive: ViaductSchema.Directive): Boolean = !filterBypassPolicyCheckDirective || directive.name != BYPASS_POLICY_CHECK_DIRECTIVE_NAME

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

    private fun ViaductSchema.Field.isTenantLocalEquivalentField(): Boolean =
        hasAppliedDirective(TENANT_LOCAL_DIRECTIVE_NAME) ||
            hasAppliedDirective(PARENT_DIRECTIVE_NAME) ||
            type.baseTypeDef.name == BACKING_DATA_SCALAR_NAME
}
