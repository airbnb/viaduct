package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema

/** Builds an extension of an input object type. */
class InputObjectTypeExtensionBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val fields = mutableListOf<InputFieldBuilder>()

    fun addField(field: InputFieldBuilder): InputObjectTypeExtensionBuilder =
        apply {
            field.claim(this)
            fields.add(field)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): InputObjectTypeExtensionBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): InputObjectTypeExtensionBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }
}
