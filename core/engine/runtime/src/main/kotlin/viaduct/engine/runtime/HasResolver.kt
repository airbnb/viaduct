package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType

/**
 * Determines whether a field has a resolver registered at its runtime coordinate.
 */
fun interface HasResolver {
    /**
     * Returns true when a field has a registered resolver.
     *
     * @param type is the concrete object type that owns the field.
     * @param fieldName is the name of the field to check.
     */
    operator fun invoke(
        type: GraphQLObjectType,
        fieldName: String
    ): Boolean

    /** return a new [HasResolver] that is the logical or of this and [other] */
    infix fun or(other: HasResolver): HasResolver = Or(this, other)

    companion object {
        /** An instance of [HasResolver] that never returns true. */
        val Never: HasResolver = const(false)

        /** An instance of [HasResolver] that always returns true. */
        val Always: HasResolver = const(true)

        /**
         * Creates a [HasResolver] that always returns the same value.
         *
         * @param value is the value returned for every field.
         */
        fun const(value: Boolean): HasResolver = HasResolver { _, _ -> value }

        /**
         * Creates a [HasResolver] backed by a dispatcher registry.
         *
         * @param dispatcherRegistry is the registry used to look up field resolver dispatchers.
         */
        fun fromRegistry(dispatcherRegistry: DispatcherRegistry): HasResolver = FromDispatcherRegistry(dispatcherRegistry)
    }
}

private class Or(val left: HasResolver, val right: HasResolver) : HasResolver {
    override fun invoke(
        type: GraphQLObjectType,
        fieldName: String
    ): Boolean = left(type, fieldName) || right(type, fieldName)
}

@JvmInline
private value class FromDispatcherRegistry(private val registry: DispatcherRegistry) : HasResolver {
    override fun invoke(
        type: GraphQLObjectType,
        fieldName: String
    ): Boolean = registry.getFieldResolverDispatcher(type.name, fieldName) != null
}
