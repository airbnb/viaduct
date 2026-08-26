package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

/** Builds an object type definition. */
class ObjectTypeBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val interfaces = mutableListOf<String>()
    internal val fields = mutableListOf<OutputFieldBuilder>()

    fun addInterface(interfaceTypeName: String): ObjectTypeBuilder =
        apply {
            interfaces.add(interfaceTypeName)
        }

    fun addField(field: OutputFieldBuilder): ObjectTypeBuilder =
        apply {
            field.claim(this)
            fields.add(field)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): ObjectTypeBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun description(description: String?): ObjectTypeBuilder =
        apply {
            state.description = description
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): ObjectTypeBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): ObjectTypeBuilder =
        apply {
            state.put(key, value)
        }
}
