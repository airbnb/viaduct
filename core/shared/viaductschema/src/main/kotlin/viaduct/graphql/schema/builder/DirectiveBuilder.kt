package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

/** Builds a directive definition. */
class DirectiveBuilder(
    name: String,
) : DefinitionBuilder(name) {
    internal val arguments = mutableListOf<ArgumentBuilder>()
    internal val locations = linkedSetOf<ViaductSchema.Directive.Location>()
    internal var repeatable = false

    fun addArgument(argument: ArgumentBuilder): DirectiveBuilder =
        apply {
            argument.claim(this)
            arguments.add(argument)
        }

    fun addLocation(location: ViaductSchema.Directive.Location): DirectiveBuilder =
        apply {
            locations.add(location)
        }

    fun repeatable(repeatable: Boolean): DirectiveBuilder =
        apply {
            this.repeatable = repeatable
        }

    fun description(description: String?): DirectiveBuilder =
        apply {
            state.description = description
        }

    fun sourceLocation(sourceLocation: ViaductSchema.SourceLocation?): DirectiveBuilder =
        apply {
            state.sourceLocation = sourceLocation
        }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ): DirectiveBuilder =
        apply {
            state.put(key, value)
        }
}
