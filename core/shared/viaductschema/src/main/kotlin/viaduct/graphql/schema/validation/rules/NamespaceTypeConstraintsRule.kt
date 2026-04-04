package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates correct usage of the @namespaceType directive.
 *
 * Rules enforced:
 * 1. Namespace fields (fields with a `@namespaceType` base type) must have no arguments, cannot have list types, and must be nullable
 * 3. Namespace types must not appear as members of any union.
 * 4. There can not be more than 1 namespace field with the same type.
 * 5. Namespace fields can only appear in the root query type or other namespace types.
 */
class NamespaceTypeConstraintsRule : ValidationRule(
    id = "NamespaceTypeConstraints",
    description = "@$DIRECTIVE_NAME types must have no-arg, non-list, nullable fields and a single namespace/root parent"
) {
    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {
        val baseTypeDef = field.type.baseTypeDef
        if (!baseTypeDef.hasAppliedDirective(DIRECTIVE_NAME)) return

        val parentType = field.containingDef
        val parentTypeName = parentType.name
        val fieldName = field.name

        if (field.hasArgs) {
            ctx.reportError(
                code = ValidationErrorCodes.NAMESPACE_TYPE_FIELD_HAS_ARGS,
                message = "Field $parentTypeName.$fieldName has @$DIRECTIVE_NAME type '${baseTypeDef.name}' but has arguments. " +
                    "Fields with @$DIRECTIVE_NAME types cannot take arguments.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        if (field.type.isList) {
            ctx.reportError(
                code = ValidationErrorCodes.NAMESPACE_TYPE_FIELD_IS_LIST,
                message = "Field $parentTypeName.$fieldName has @$DIRECTIVE_NAME type '${baseTypeDef.name}' as a list. " +
                    "@$DIRECTIVE_NAME types cannot appear in lists.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        if (!field.type.isNullable) {
            ctx.reportError(
                code = ValidationErrorCodes.NAMESPACE_TYPE_FIELD_IS_NON_NULL,
                message = "Field $parentTypeName.$fieldName has @$DIRECTIVE_NAME type '${baseTypeDef.name}' but is non-null. " +
                    "Namespace fields must be nullable.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }

        val isValidParent = parentTypeName == ctx.schema.queryTypeDef?.name || parentType.hasAppliedDirective(DIRECTIVE_NAME)
        if (!isValidParent) {
            ctx.reportError(
                code = ValidationErrorCodes.NAMESPACE_TYPE_INVALID_PARENT,
                message = "Field $parentTypeName.$fieldName has @$DIRECTIVE_NAME type '${baseTypeDef.name}', " +
                    "but '$parentTypeName' is not the root query type nor a @$DIRECTIVE_NAME type.",
                location = SchemaLocation.ofField(parentTypeName, fieldName).withSourceLocation(field.sourceLocation)
            )
        }
    }

    override fun visitUnion(
        ctx: ValidationContext,
        union: ViaductSchema.Union
    ) {
        union.possibleObjectTypes.forEach { member ->
            if (member.hasAppliedDirective(DIRECTIVE_NAME)) {
                ctx.reportError(
                    code = ValidationErrorCodes.NAMESPACE_TYPE_IN_UNION,
                    message = "@$DIRECTIVE_NAME type '${member.name}' is a member of union '${union.name}'. " +
                        "@$DIRECTIVE_NAME types cannot appear in unions.",
                    location = SchemaLocation.ofType(union.name)
                )
            }
        }
    }

    override fun visitSchema(ctx: ValidationContext) {
        val namespaceTypeParents = mutableMapOf<String, MutableList<ViaductSchema.Field>>()

        for (typeDef in ctx.schema.types.values) {
            if (typeDef !is ViaductSchema.Record) continue
            for (field in typeDef.fields) {
                val baseTypeDef = field.type.baseTypeDef
                if (baseTypeDef.hasAppliedDirective(DIRECTIVE_NAME)) {
                    namespaceTypeParents.getOrPut(baseTypeDef.name) { mutableListOf() }.add(field)
                }
            }
        }

        for ((namespaceTypeName, fields) in namespaceTypeParents) {
            if (fields.size > 1) {
                val allFields = fields.joinToString(", ") { "${it.containingDef.name}.${it.name}" }
                ctx.reportError(
                    code = ValidationErrorCodes.NAMESPACE_TYPE_MULTIPLE_PARENTS,
                    message = "@$DIRECTIVE_NAME type '$namespaceTypeName' cannot be the type of multiple fields: $allFields.",
                    location = SchemaLocation.ofType(namespaceTypeName)
                )
            }
        }
    }

    companion object {
        const val DIRECTIVE_NAME = "namespaceType"
    }
}
