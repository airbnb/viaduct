package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates correct usage of the [BackingData] scalar type and [@backingData][BACKING_DATA_DIRECTIVE] directive.
 *
 * Rules enforced:
 * 1. [BackingData] and `@backingData` may only be used on object fields that are not inherited from interfaces.
 * 2. A field with a `@backingData` directive **must** have [BackingData] as its base type.
 * 3. A field with [BackingData] as its base type **must** have a `@backingData` directive.
 */
class BackingDataFieldsRule : ValidationRule(
    id = "BackingDataFields",
    description = "BackingData type and @backingData directive must be used together on object-only fields"
) {
    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {
        val isBackingDataType = field.type.baseTypeDef.name == BACKING_DATA_SCALAR
        val hasBackingDataDirective = field.hasAppliedDirective(BACKING_DATA_DIRECTIVE)
        val usesBackingData = isBackingDataType || hasBackingDataDirective
        val typeName = field.containingDef.name
        val fieldName = field.name

        when {
            field.containingDef is ViaductSchema.Input && isBackingDataType ->
                ctx.reportError(
                    code = ValidationErrorCodes.BACKING_DATA_ON_INPUT_FIELD,
                    message = "BackingData cannot be used on input field $typeName.$fieldName. " +
                        "It can only be used on object fields.",
                    location = SchemaLocation.ofField(typeName, fieldName).withSourceLocation(field.sourceLocation)
                )

            field.containingDef is ViaductSchema.Interface && usesBackingData ->
                ctx.reportError(
                    code = ValidationErrorCodes.BACKING_DATA_ON_INTERFACE_FIELD,
                    message = "BackingData cannot be used on interface field $typeName.$fieldName. " +
                        "It can only be used on object fields.",
                    location = SchemaLocation.ofField(typeName, fieldName).withSourceLocation(field.sourceLocation)
                )

            field.isOverride && usesBackingData ->
                ctx.reportError(
                    code = ValidationErrorCodes.BACKING_DATA_IMPLEMENTED_INTERFACE_FIELD,
                    message = "Field $typeName.$fieldName uses BackingData but implements an interface field. " +
                        "BackingData is not allowed on fields inherited from interfaces.",
                    location = SchemaLocation.ofField(typeName, fieldName).withSourceLocation(field.sourceLocation)
                )

            field.containingDef !is ViaductSchema.Input && isBackingDataType && !hasBackingDataDirective ->
                ctx.reportError(
                    code = ValidationErrorCodes.BACKING_DATA_MISSING_DIRECTIVE,
                    message = "Missing @backingData directive. Field $typeName.$fieldName has a BackingData base " +
                        "type, so it must have a @backingData directive.",
                    location = SchemaLocation.ofField(typeName, fieldName).withSourceLocation(field.sourceLocation)
                )

            field.containingDef !is ViaductSchema.Input && !isBackingDataType && hasBackingDataDirective ->
                ctx.reportError(
                    code = ValidationErrorCodes.BACKING_DATA_MISSING_TYPE,
                    message = "Missing BackingData type. Field $typeName.$fieldName has a @backingData directive, " +
                        "which can only be used on fields with a BackingData base type.",
                    location = SchemaLocation.ofField(typeName, fieldName).withSourceLocation(field.sourceLocation)
                )
        }
    }

    companion object {
        private const val BACKING_DATA_SCALAR = "BackingData"
        private const val BACKING_DATA_DIRECTIVE = "backingData"
    }
}
