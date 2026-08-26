package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema

/** Builds an extension of a union type. */
class UnionTypeExtensionBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val members = mutableListOf<String>()

    fun addMember(objectTypeName: String): UnionTypeExtensionBuilder =
        apply {
            members.add(objectTypeName)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): UnionTypeExtensionBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): UnionTypeExtensionBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }
}
