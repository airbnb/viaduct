package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema

/** Builds an extension of an enum type. */
class EnumTypeExtensionBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val values = mutableListOf<EnumValueBuilder>()

    fun addValue(value: EnumValueBuilder): EnumTypeExtensionBuilder =
        apply {
            value.claim(this)
            values.add(value)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): EnumTypeExtensionBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): EnumTypeExtensionBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }
}
