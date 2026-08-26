package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

/** Builds a union type definition. */
class UnionTypeBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val members = mutableListOf<String>()

    fun addMember(objectTypeName: String): UnionTypeBuilder =
        apply {
            members.add(objectTypeName)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): UnionTypeBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun description(description: String?): UnionTypeBuilder =
        apply {
            state.description = description
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): UnionTypeBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): UnionTypeBuilder =
        apply {
            state.put(key, value)
        }
}
