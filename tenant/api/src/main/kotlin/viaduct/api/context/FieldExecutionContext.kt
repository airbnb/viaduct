package viaduct.api.context

import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.StableApi

/**
 * An [ExecutionContext] provided to field resolvers
 */
@StableApi
interface FieldExecutionContext<T : Object, Q : Query, A : Arguments, O : CompositeOutput> : BaseFieldExecutionContext<Q, A, O> {
    /**
     * A value of [T], with any (and only) selections from [viaduct.api.Resolver.objectValueFragment]
     * populated.
     * Attempting to access fields not declared in [viaduct.api.Resolver.objectValueFragment] will
     * throw a runtime exception
     */
    val objectValue: T
}
