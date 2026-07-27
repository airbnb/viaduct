package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.isNode
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates that the `type` parameter of `@idOf` directives references an existing type
 * that implements the `Node` interface.
 */
class IdOfTypeValidationRule : ValidationRule(
    id = "IdOfType",
    description = "@idOf type parameter must reference an existing Node type"
) {
    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {
        val typeName = getIdOfTypeName(field.appliedDirectives) ?: return
        val location = SchemaLocation.ofField(field.containingDef.name, field.name)
            .withSourceLocation(field.sourceLocation)
        checkType(ctx, typeName, location)
    }

    override fun visitFieldArg(
        ctx: ValidationContext,
        arg: ViaductSchema.FieldArg
    ) {
        val typeName = getIdOfTypeName(arg.appliedDirectives) ?: return
        val field = arg.containingDef
        val location = SchemaLocation(
            listOf(field.containingDef.name, field.name, arg.name)
        ).withSourceLocation(arg.sourceLocation)
        checkType(ctx, typeName, location)
    }

    private fun getIdOfTypeName(directives: Collection<ViaductSchema.AppliedDirective<*>>): String? {
        val idOfDirective = directives.find { it.name == ID_OF_DIRECTIVE } ?: return null
        return (idOfDirective.arguments["type"] as? ViaductSchema.StringLiteral)?.value
    }

    private fun checkType(
        ctx: ValidationContext,
        typeName: String,
        location: SchemaLocation
    ) {
        val typeDef = ctx.schema.types[typeName]
        if (typeDef == null) {
            ctx.reportError(
                code = ValidationErrorCodes.ID_OF_TYPE_NOT_FOUND,
                message = "@idOf(type: \"$typeName\") references undefined type `$typeName`",
                location = location
            )
        } else if (!typeDef.isNode) {
            ctx.reportError(
                code = ValidationErrorCodes.ID_OF_TYPE_NOT_NODE,
                message = "@idOf(type: \"$typeName\") references non-Node type `$typeName`",
                location = location
            )
        }
    }

    companion object {
        private const val ID_OF_DIRECTIVE = "idOf"
    }
}
