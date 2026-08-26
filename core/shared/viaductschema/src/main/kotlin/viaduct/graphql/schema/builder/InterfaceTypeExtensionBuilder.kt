package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema

/** Builds an extension of an interface type. */
class InterfaceTypeExtensionBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val interfaces = mutableListOf<String>()
    internal val fields = mutableListOf<OutputFieldBuilder>()

    fun addInterface(interfaceTypeName: String): InterfaceTypeExtensionBuilder =
        apply {
            interfaces.add(interfaceTypeName)
        }

    fun addField(field: OutputFieldBuilder): InterfaceTypeExtensionBuilder =
        apply {
            field.claim(this)
            fields.add(field)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): InterfaceTypeExtensionBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): InterfaceTypeExtensionBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }
}
