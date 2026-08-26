package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

/** Builds a field for an input object type. */
class InputFieldBuilder(
    val name: String,
    val type: TypeExprBuilder,
) {
    internal val state = BuilderElementState(this)

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): InputFieldBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun defaultValue(defaultValue: ViaductSchema.Literal): InputFieldBuilder =
        apply {
            state.defaultValue = defaultValue
        }

    fun description(description: String?): InputFieldBuilder =
        apply {
            state.description = description
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): InputFieldBuilder =
        apply {
            state.put(key, value)
        }

    internal fun claim(owner: Any) = state.claim(owner)
}
