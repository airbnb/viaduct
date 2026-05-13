package viaduct.api.resolver

import viaduct.apiannotations.StableApi

/**
 * Declares the set of GraphQL variables that a [VariablesProvider] or [Resolver] binding will supply.
 *
 * Place this annotation on a [VariablesProvider] implementation class. Each element of [types] must
 * describe one variable as `"name: GraphQLType"`. The same variable names must appear as keys in the
 * map returned by [VariablesProvider.provide].
 */
@StableApi
@Target(AnnotationTarget.CLASS)
annotation class Variables(
    /**
     * Each element describes one variable as `name: Type`.
     * All spaces are ignored.
     *
     * Example:
     * ```
     *   @Variables("foo: Int", "bar: String!")
     * ```
     */
    vararg val types: String
)
