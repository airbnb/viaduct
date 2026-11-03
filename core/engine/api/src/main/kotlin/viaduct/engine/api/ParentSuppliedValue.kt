package viaduct.engine.api

import graphql.schema.GraphQLObjectType

/**
 * Returned by a resolver whose value already contains some of the fields below it, including
 * fields that have resolvers of their own, such as a delegated response from a service block.
 *
 * The engine unwraps the value and resolves it with [ResolutionPolicy.STANDARD]. When the field is
 * Mat-backed, Mat reads the supplied fields from this value and fetches a missing one by running
 * this field's resolver again. Every other field below the value runs its own resolver.
 */
class ParentSuppliedValue(
    val value: Any?,
    val isSupplied: (type: GraphQLObjectType, fieldName: String) -> Boolean,
) {
    init {
        require(value !is ParentSuppliedValue)
        require(value !is ParentManagedValue)
        require(value !is StandardResolutionValue)
    }
}
