package viaduct.api.testing.types

import viaduct.api.reflect.RootObjectField
import viaduct.api.types.Arguments
import viaduct.api.types.Object

/**
 * Pre-baked stub for a `ctx.rootFieldRef(field, args)` call made by the resolver
 * under test. Pass one or more of these via the `rootFieldRefValues` spec property
 * to mock out root field references.
 *
 * Stubs are matched on the exact `(field, arguments)` pair: the resolver's call
 * arguments must equal [arguments] for [value] to be returned. Argument-less root
 * fields use [Arguments.NoArguments].
 */
class RootFieldRefStub<A : Arguments, T : Object>(
    val field: RootObjectField<*, T, A>,
    val arguments: A,
    val value: T,
)
