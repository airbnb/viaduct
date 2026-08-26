package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

/** Builds an enum type definition. */
class EnumTypeBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val values = mutableListOf<EnumValueBuilder>()

    fun addValue(value: EnumValueBuilder): EnumTypeBuilder =
        apply {
            value.claim(this)
            values.add(value)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): EnumTypeBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun description(description: String?): EnumTypeBuilder =
        apply {
            state.description = description
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): EnumTypeBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): EnumTypeBuilder =
        apply {
            state.put(key, value)
        }
}
