package viaduct.graphql.schema.builder

import viaduct.utils.collections.HMap

/** Builds a field for an object or interface type. */
class OutputFieldBuilder(
    val name: String,
    val type: TypeExprBuilder,
) {
    internal val state = BuilderElementState(this)
    internal val arguments = mutableListOf<ArgumentBuilder>()

    fun addArgument(argument: ArgumentBuilder): OutputFieldBuilder =
        apply {
            argument.claim(this)
            arguments.add(argument)
        }

    fun addAppliedDirective(directive: AppliedDirectiveBuilder): OutputFieldBuilder =
        apply {
            state.addAppliedDirective(directive)
        }

    fun description(description: String?): OutputFieldBuilder =
        apply {
            state.description = description
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): OutputFieldBuilder =
        apply {
            state.put(key, value)
        }

    internal fun claim(owner: Any) = state.claim(owner)
}
