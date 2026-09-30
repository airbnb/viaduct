package model.lowering

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.builder.InterfaceTypeBuilder
import viaduct.graphql.schema.builder.InterfaceTypeExtensionBuilder
import viaduct.graphql.schema.builder.ObjectTypeExtensionBuilder
import viaduct.graphql.schema.builder.OutputFieldBuilder
import viaduct.graphql.schema.builder.TypeExprBuilder
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationRule

internal class TypenameProxyRule :
    ValidationRule(
        id = "QPlanTypenameProxy",
        description = "Adds ordinary fields for internal typename demand",
    ) {
    override fun visitSchema(ctx: ValidationContext) {
        (ctx as SchemaLoweringContext).builder.addDefinition(
            InterfaceTypeBuilder(ALL_SOURCE_OBJECTS_TYPE)
                .addField(typenameField()),
        )
    }

    override fun visitInterface(
        ctx: ValidationContext,
        iface: ViaductSchema.Interface,
    ) {
        (ctx as SchemaLoweringContext).builder.addDefinition(
            InterfaceTypeExtensionBuilder(iface.name)
                .addField(typenameField()),
        )
    }

    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object,
    ) {
        (ctx as SchemaLoweringContext).builder.addDefinition(
            ObjectTypeExtensionBuilder(obj.name)
                .addInterface(ALL_SOURCE_OBJECTS_TYPE)
                .addField(typenameField()),
        )
    }

    private fun typenameField(): OutputFieldBuilder =
        OutputFieldBuilder(
            LOWERED_TYPENAME_FIELD,
            TypeExprBuilder("String", nullable = false),
        )
}
