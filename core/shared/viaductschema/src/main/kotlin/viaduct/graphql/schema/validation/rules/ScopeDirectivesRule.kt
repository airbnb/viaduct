package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

class ScopeDirectivesRule : ValidationRule(
    id = "ScopeDirectives",
    description = "Validates @scope directives and scoped schema membership consistency"
) {
    override fun visitTypeDef(
        ctx: ValidationContext,
        typeDef: ViaductSchema.TypeDef
    ) {
        if (typeDef.name.startsWith("__")) return

        validateExtensionScopeExpansion(ctx, typeDef)
        when (typeDef) {
            is ViaductSchema.Union -> validateUnionScopes(ctx, typeDef)
            is ViaductSchema.OutputRecord -> validateOutputRecordScopes(ctx, typeDef)
            else -> {}
        }
    }

    /**
     * Checks that type extensions don't expand scope.
     */
    private fun validateExtensionScopeExpansion(
        ctx: ValidationContext,
        typeDef: ViaductSchema.TypeDef
    ) {
        val baseExtension = typeDef.extensions.firstOrNull { it.isBase } ?: return
        val typeScopes = baseExtension.expandedScopes
        if (typeScopes.contains(WILDCARD_SCOPE)) return

        for (extension in typeDef.extensions.filterNot { it.isBase }) {
            val extensionScopes = extension.scopes ?: emptyList()
            if (!typeScopes.containsAll(extensionScopes)) {
                ctx.reportError(
                    code = ValidationErrorCodes.SCOPE_EXTENSION_EXPANDS_BASE,
                    message = "Extension definition on type ${typeDef.name} cannot expand scope from " +
                        "${baseExtension.scopes} to $extensionScopes",
                    location = SchemaLocation.ofType(typeDef.name).withSourceLocation(extension.sourceLocation)
                )
            }
        }
    }

    /**
     * Checks that the scopes of each union extension is exactly the intersection of the union's scopes and the scopes
     * of each of the object type members added in the extension.
     * This disallows the situation where a union and its member object are both in scope but the union membership
     * relationship is out of scope.
     */
    private fun validateUnionScopes(
        ctx: ValidationContext,
        union: ViaductSchema.Union
    ) {
        val baseExtension = union.extensions.firstOrNull { it.isBase } ?: return
        val unionScopes = baseExtension.expandedScopes

        for (extension in union.extensions.filterNot { it.isBase }) {
            val extensionScopes = extension.expandedScopes
            for (member in extension.members) {
                val memberBaseExtension = member.extensions.firstOrNull { it.isBase }
                val memberScopes = memberBaseExtension?.expandedScopes ?: emptySet()
                if (unionScopes.intersect(memberScopes) != extensionScopes) {
                    ctx.reportError(
                        code = ValidationErrorCodes.UNION_EXTENSION_SCOPE_INTERSECTION_MISMATCH,
                        message = "Extension definition on union type ${union.name} has scopes ${extension.scopes} " +
                            "that is not the intersection of ${union.name} scopes ${baseExtension.scopes} " +
                            "and ${member.name} scopes ${memberBaseExtension?.scopes}",
                        location = SchemaLocation.ofType(union.name).withSourceLocation(extension.sourceLocation)
                    )
                }
            }
        }
    }

    private fun validateOutputRecordScopes(
        ctx: ValidationContext,
        outputRecord: ViaductSchema.OutputRecord
    ) {
        val baseExtension = outputRecord.extensions.firstOrNull { it.isBase } ?: return
        val outputRecordScopes = baseExtension.expandedScopes
        validateOutputRecordHasFieldsInEveryScope(ctx, outputRecord, baseExtension)

        for (extension in outputRecord.extensions.filterNot { it.isBase }) {
            validateScopedMembersDeclareScope(ctx, outputRecord, extension)

            val extensionScopes = extension.scopes ?: emptyList()
            for (superInterface in extension.supers) {
                val superBaseExtension = superInterface.extensions.firstOrNull { it.isBase }
                val superScopes = superBaseExtension?.expandedScopes ?: emptySet()
                // Check that the extension scopes are a subset of the intersection of the record and super scopes.
                if (!outputRecordScopes.intersect(superScopes).containsAll(extensionScopes)) {
                    ctx.reportError(
                        code = ValidationErrorCodes.IMPLEMENTED_INTERFACE_EXTENSION_SCOPE_MISMATCH,
                        message = "Extension definition on ${outputRecord.name} that implements interface " +
                            "${superInterface.name} has scopes $extensionScopes that is not a subset of the " +
                            "intersection of ${outputRecord.name} scopes ${baseExtension.scopes} and " +
                            "${superInterface.name} scopes ${superBaseExtension?.scopes}",
                        location = SchemaLocation.ofType(outputRecord.name).withSourceLocation(extension.sourceLocation)
                    )
                }

                // Check that the record includes all the fields necessary to implement the super in all scopes
                // where the super relationship exists.
                validateImplementedInterfaceFieldsAreInScope(
                    ctx,
                    outputRecord,
                    superInterface,
                    extensionScopes
                )
            }
        }
    }

    private fun validateOutputRecordHasFieldsInEveryScope(
        ctx: ValidationContext,
        outputRecord: ViaductSchema.OutputRecord,
        baseExtension: ViaductSchema.ExtensionWithSupers<*, ViaductSchema.Field>
    ) {
        for (scope in baseExtension.scopes.orEmpty().distinct()) {
            val hasFieldDeclaredInScope = outputRecord.fields.any {
                !it.hasAppliedDirective(TENANT_LOCAL_DIRECTIVE_NAME) &&
                    it.containingExtension.appliedDirectives.includesScope(scope)
            }
            if (!hasFieldDeclaredInScope) {
                ctx.reportError(
                    code = ValidationErrorCodes.OBJECT_OR_INTERFACE_SCOPE_WITHOUT_FIELDS,
                    message = "${outputRecord.typeKeyword} ${outputRecord.name} declares scope $scope " +
                        "but has no fields in that scope",
                    location = SchemaLocation.ofType(outputRecord.name)
                        .withSourceLocation(baseExtension.sourceLocation)
                )
            }
        }
    }

    private fun validateScopedMembersDeclareScope(
        ctx: ValidationContext,
        outputRecord: ViaductSchema.OutputRecord,
        extension: ViaductSchema.ExtensionWithSupers<*, ViaductSchema.Field>
    ) {
        if (extension.hasAppliedDirective(SCOPE_DIRECTIVE_NAME)) return

        val scopedFields = extension.members.filterNot { it.hasAppliedDirective(TENANT_LOCAL_DIRECTIVE_NAME) }
        val scopedSupers = extension.supers
        if (scopedFields.isEmpty() && scopedSupers.isEmpty()) return

        ctx.reportError(
            code = ValidationErrorCodes.OBJECT_OR_INTERFACE_EXTENSION_SCOPE_DIRECTIVE_MISSING,
            message = "Extension definition on ${outputRecord.typeKeyword} ${outputRecord.name} must declare @scope " +
                "because it adds ${scopedMemberDescription(scopedFields, scopedSupers)}.",
            location = SchemaLocation.ofType(outputRecord.name).withSourceLocation(extension.sourceLocation)
        )
    }

    private fun validateImplementedInterfaceFieldsAreInScope(
        ctx: ValidationContext,
        outputRecord: ViaductSchema.OutputRecord,
        superInterface: ViaductSchema.Interface,
        extensionScopes: List<String>
    ) {
        for (superField in superInterface.fields.filterNot { it.hasAppliedDirective(TENANT_LOCAL_DIRECTIVE_NAME) }) {
            val field = outputRecord.fields.firstOrNull { it.name == superField.name } ?: continue
            for (extensionScope in extensionScopes) {
                if (!field.isInScope(extensionScope)) {
                    ctx.reportError(
                        code = ValidationErrorCodes.IMPLEMENTED_INTERFACE_FIELD_SCOPE_MISSING,
                        message = "${outputRecord.name}.${field.name} is required to implement ${superInterface.name}, " +
                            "but is not in scope $extensionScope",
                        location = SchemaLocation.ofField(outputRecord.name, field.name)
                            .withSourceLocation(field.sourceLocation)
                    )
                }
            }
        }
    }

    private fun scopedMemberDescription(
        scopedFields: Collection<ViaductSchema.Field>,
        scopedSupers: Collection<ViaductSchema.Interface>
    ): String =
        buildList {
            if (scopedFields.isNotEmpty()) {
                add("non-tenant-local field(s): [${scopedFields.joinToString(", ") { it.name }}]")
            }
            if (scopedSupers.isNotEmpty()) {
                add("implemented interface(s): [${scopedSupers.joinToString(", ") { it.name }}]")
            }
        }.joinToString(" and ")

    private val ViaductSchema.OutputRecord.typeKeyword: String
        get() =
            when (this) {
                is ViaductSchema.Interface -> "interface"
                is ViaductSchema.Object -> "type"
                else -> "type"
            }

    private fun ViaductSchema.Def.isInScope(scope: String): Boolean =
        when (this) {
            is ViaductSchema.Enum,
            is ViaductSchema.Input,
            is ViaductSchema.Interface,
            is ViaductSchema.Object,
            is ViaductSchema.Union -> appliedDirectives.includesScope(scope)
            is ViaductSchema.Field ->
                !hasAppliedDirective(TENANT_LOCAL_DIRECTIVE_NAME) &&
                    containingExtension.appliedDirectives.includesScope(scope) &&
                    type.baseTypeDef.isInScope(scope)
            is ViaductSchema.EnumValue -> containingExtension.appliedDirectives.includesScope(scope)
            else -> true
        }

    private fun Iterable<ViaductSchema.AppliedDirective<*>>.includesScope(scope: String): Boolean {
        // If a definition is in a non-private scope, it's automatically also in the private version of that scope, e.g.
        // scope(to: ["listing-block"]) is the same as scope(to: ["listing-block", "listing-block:private"]).
        // So if the given scope is :private, expand the check in case the :private scope wasn't explicitly set.
        val scopes = if (scope.endsWith(PRIVATE_SCOPE_SUFFIX)) {
            setOf(scope, scope.removeSuffix(PRIVATE_SCOPE_SUFFIX))
        } else {
            setOf(scope)
        }
        filter { it.name == SCOPE_DIRECTIVE_NAME }.forEach { directive ->
            (directive.arguments[SCOPE_TO_ARGUMENT] as ViaductSchema.ListLiteral).forEach {
                val value = (it as ViaductSchema.StringLiteral).value
                if (value in scopes || value == WILDCARD_SCOPE) return true
            }
        }
        return false
    }

    /**
     * If the extension has a scope directive, returns the scopes listed in that directive. Otherwise, returns null.
     * Throws an exception if the directive is not well-formed.
     */
    private val ViaductSchema.Extension<*, *>.scopes: List<String>?
        get() {
            val scopeDirectives = appliedDirectives.filter { it.name == SCOPE_DIRECTIVE_NAME }
            if (scopeDirectives.isEmpty()) return null

            // scope is a repeatable directive
            return scopeDirectives.flatMap { directive ->
                (directive.arguments[SCOPE_TO_ARGUMENT] as ViaductSchema.ListLiteral).map {
                    (it as ViaductSchema.StringLiteral).value
                }
            }
        }

    /**
     * If a definition is in a non-private scope, it's automatically also in the private version of that scope, e.g.
     * scope(to: ["listing-block"]) is the same as scope(to: ["listing-block", "listing-block:private"]).
     */
    private val ViaductSchema.Extension<*, *>.expandedScopes: Set<String>
        get() {
            val scopes = this.scopes ?: return emptySet()
            val expandedScopes = scopes.toMutableSet()
            for (scope in scopes) {
                if (!scope.endsWith(PRIVATE_SCOPE_SUFFIX)) {
                    expandedScopes.add(scope.split(":").first() + PRIVATE_SCOPE_SUFFIX)
                }
            }
            return expandedScopes
        }

    companion object {
        private const val SCOPE_DIRECTIVE_NAME = "scope"
        private const val SCOPE_TO_ARGUMENT = "to"
        private const val TENANT_LOCAL_DIRECTIVE_NAME = "tenantLocal"
        private const val PRIVATE_SCOPE_SUFFIX = ":private"
        private const val WILDCARD_SCOPE = "*"
    }
}
