package viaduct.graphql.schema.builder

import viaduct.utils.collections.HMap

/** Builds a value contained by an enum definition or extension. */
class EnumValueBuilder(
    val name: String,
) {
    internal val state = BuilderElementState(this)

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): EnumValueBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun description(description: String?): EnumValueBuilder =
        apply {
            state.description = description
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): EnumValueBuilder =
        apply {
            state.put(key, value)
        }

    internal fun claim(owner: Any) = state.claim(owner)
}
