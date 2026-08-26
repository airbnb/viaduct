package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema

/** Builds an extension of a scalar type. */
class ScalarTypeExtensionBuilder(
    name: String,
) : DefinitionBuilder(name) {
    fun addAppliedDirective(directive: AppliedDirectiveBuilder): ScalarTypeExtensionBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): ScalarTypeExtensionBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }
}
