package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

/**
 * Builds an argument for either a field or a directive definition.
 */
class ArgumentBuilder(
    val name: String,
    val type: TypeExprBuilder,
) {
    internal val state = BuilderElementState(this)

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): ArgumentBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun defaultValue(defaultValue: ViaductSchema.Literal): ArgumentBuilder =
        apply {
            state.defaultValue = defaultValue
        }

    fun description(description: String?): ArgumentBuilder =
        apply {
            state.description = description
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): ArgumentBuilder =
        apply {
            state.put(key, value)
        }

    internal fun claim(owner: Any) = state.claim(owner)
}
