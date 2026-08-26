package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

/** Builds an interface type definition. */
class InterfaceTypeBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val interfaces = mutableListOf<String>()
    internal val fields = mutableListOf<OutputFieldBuilder>()

    fun addInterface(interfaceTypeName: String): InterfaceTypeBuilder =
        apply {
            interfaces.add(interfaceTypeName)
        }

    fun addField(field: OutputFieldBuilder): InterfaceTypeBuilder =
        apply {
            field.claim(this)
            fields.add(field)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): InterfaceTypeBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun description(description: String?): InterfaceTypeBuilder =
        apply {
            state.description = description
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): InterfaceTypeBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): InterfaceTypeBuilder =
        apply {
            state.put(key, value)
        }
}
