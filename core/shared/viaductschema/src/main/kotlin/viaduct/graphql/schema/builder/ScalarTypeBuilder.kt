package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

/** Builds a scalar type definition. */
class ScalarTypeBuilder(
    name: String,
) : DefinitionBuilder(name) {
    fun addAppliedDirective(directive: AppliedDirectiveBuilder): ScalarTypeBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun description(description: String?): ScalarTypeBuilder =
        apply {
            state.description = description
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): ScalarTypeBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): ScalarTypeBuilder =
        apply {
            state.put(key, value)
        }
}
