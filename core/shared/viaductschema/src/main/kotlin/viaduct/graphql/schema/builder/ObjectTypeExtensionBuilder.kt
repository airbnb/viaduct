package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema

/** Builds an extension of an object type. */
class ObjectTypeExtensionBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val interfaces = mutableListOf<String>()
    internal val fields = mutableListOf<OutputFieldBuilder>()

    fun addInterface(interfaceTypeName: String): ObjectTypeExtensionBuilder =
        apply {
            interfaces.add(interfaceTypeName)
        }

    fun addField(field: OutputFieldBuilder): ObjectTypeExtensionBuilder =
        apply {
            field.claim(this)
            fields.add(field)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): ObjectTypeExtensionBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): ObjectTypeExtensionBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }
}
