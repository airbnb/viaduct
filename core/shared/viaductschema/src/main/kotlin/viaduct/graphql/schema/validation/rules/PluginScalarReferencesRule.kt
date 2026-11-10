package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.SchemaProvenance
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

class PluginScalarReferencesRule : ValidationRule("PluginScalarReferences") {
    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {
        check(ctx, field, false, SchemaLocation.ofField(field.containingDef.name, field.name))
    }

    override fun visitFieldArg(
        ctx: ValidationContext,
        arg: ViaductSchema.FieldArg
    ) {
        val field = arg.containingDef
        check(ctx, arg, true, SchemaLocation(listOf(field.containingDef.name, field.name, arg.name)))
    }

    override fun visitDirectiveArg(
        ctx: ValidationContext,
        arg: ViaductSchema.DirectiveArg
    ) {
        check(ctx, arg, true, SchemaLocation(listOf("@${arg.containingDef.name}", arg.name)))
    }

    private fun check(
        ctx: ValidationContext,
        declaration: ViaductSchema.HasDefaultValue,
        isArgument: Boolean,
        location: SchemaLocation,
    ) {
        val scalar = declaration.type.baseTypeDef as? ViaductSchema.Scalar ?: return
        if (!SchemaProvenance.isPluginScalar(scalar)) return
        val owner = SchemaProvenance.parse(scalar.sourceLocation?.sourceName)?.supplyingPlugin
        val supplier = SchemaProvenance.parse(declaration.sourceLocation?.sourceName)?.supplyingPlugin
        if (!isArgument || supplier != owner) {
            ctx.reportError(
                ValidationErrorCodes.PLUGIN_SCALAR_REFERENCE_NOT_ALLOWED,
                "Plugin scalar '${scalar.name}' from ${scalar.sourceLocation?.sourceName} may only type arguments " +
                    "declared by plugin '$owner'; this declaration is supplied by ${declaration.sourceLocation?.sourceName}.",
                location.withSourceLocation(declaration.sourceLocation),
            )
        }
    }
}
