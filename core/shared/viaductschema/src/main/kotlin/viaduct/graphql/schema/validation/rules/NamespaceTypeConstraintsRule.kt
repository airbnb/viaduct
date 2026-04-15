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

    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) {
        if (!obj.hasAppliedDirective(DIRECTIVE_NAME)) return

        val inboundFields = ctx.reverseSchema.inboundFields(obj)
        if (inboundFields.size != 1) {
            val message = if (inboundFields.isEmpty()) {
                "@$DIRECTIVE_NAME type '${obj.name}' must be referenced by exactly one field, but is not referenced by any field."
            } else {
                val allFields = inboundFields.joinToString(", ") { "${it.containingDef.name}.${it.name}" }
                "@$DIRECTIVE_NAME type '${obj.name}' must be referenced by exactly one field, but is referenced by ${inboundFields.size}: $allFields."
            }
            ctx.reportError(
                code = ValidationErrorCodes.NAMESPACE_TYPE_PARENT_COUNT_NOT_ONE,
                message = message,
                location = SchemaLocation.ofType(obj.name)
            )
        }
    }

    companion object {
        const val DIRECTIVE_NAME = "namespaceType"
    }
}
