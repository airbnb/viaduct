package model.lowering

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationRule

internal class ReservedNamesRule :
    ValidationRule(
        id = "QPlanReservedNames",
        description = "Reserves qplan's generated schema namespace",
    ) {
    override fun visitDirective(
        ctx: ValidationContext,
        directive: ViaductSchema.Directive,
    ) = checkName(ctx, directive.name, SchemaLocation.ofDirective(directive.name))

    override fun visitDirectiveArg(
        ctx: ValidationContext,
        arg: ViaductSchema.DirectiveArg,
    ) = checkName(
        ctx,
        arg.name,
        SchemaLocation(listOf("@${arg.containingDef.name}", arg.name)),
    )

    override fun visitTypeDef(
        ctx: ValidationContext,
        typeDef: ViaductSchema.TypeDef,
    ) = checkName(ctx, typeDef.name, SchemaLocation.ofType(typeDef.name))

    override fun visitEnumValue(
        ctx: ValidationContext,
        value: ViaductSchema.EnumValue,
    ) = checkName(
        ctx,
        value.name,
        SchemaLocation(listOf(value.containingDef.name, value.name)),
    )

    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field,
    ) = checkName(
        ctx,
        field.name,
        SchemaLocation.ofField(field.containingDef.name, field.name),
    )

    override fun visitFieldArg(
        ctx: ValidationContext,
        arg: ViaductSchema.FieldArg,
    ) = checkName(
        ctx,
        arg.name,
        SchemaLocation(
            listOf(
                arg.containingDef.containingDef.name,
                arg.containingDef.name,
                arg.name,
            ),
        ),
    )

    private fun checkName(
        ctx: ValidationContext,
        name: String,
        location: SchemaLocation,
    ) {
        if (LOWERING_SYNTHETIC_NAME_TOKEN in name) {
            ctx.reportError(
                code = "QPLAN_RESERVED_NAME",
                message =
                    "Source schema names cannot contain reserved token " +
                        "$LOWERING_SYNTHETIC_NAME_TOKEN: $name",
                location = location,
            )
        }
        if (name == VIADUCT_IGNORE_SYMBOL) {
            ctx.reportError(
                code = "QPLAN_RESERVED_NAME",
                message = "Source schema names cannot use reserved symbol $VIADUCT_IGNORE_SYMBOL",
                location = location,
            )
        }
    }
}
