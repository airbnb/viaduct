package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

/** Builds an input object type definition. */
class InputObjectTypeBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val fields = mutableListOf<InputFieldBuilder>()

    fun addField(field: InputFieldBuilder): InputObjectTypeBuilder =
        apply {
            field.claim(this)
            fields.add(field)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): InputObjectTypeBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun description(description: String?): InputObjectTypeBuilder =
        apply {
            state.description = description
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): InputObjectTypeBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): InputObjectTypeBuilder =
        apply {
            state.put(key, value)
        }
}
