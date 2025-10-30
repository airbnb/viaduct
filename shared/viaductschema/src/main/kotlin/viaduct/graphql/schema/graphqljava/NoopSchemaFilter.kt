package viaduct.graphql.schema.graphqljava

import viaduct.graphql.schema.SchemaFilter
import viaduct.graphql.schema.ViaductSchema

class NoopSchemaFilter : SchemaFilter {
    override fun includeTypeDef(typeDef: ViaductSchema.TypeDef) = true

    override fun includeField(field: ViaductSchema.Field) = true

    override fun includeEnumValue(enumValue: ViaductSchema.EnumValue) = true

    override fun includeSuper(
        record: ViaductSchema.HasExtensionsWithSupers<*, *>,
        superInterface: ViaductSchema.Interface
    ) = true
}
