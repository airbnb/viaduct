package viaduct.api.context

import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
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
     * throw a runtime exception.
     *
     * This property provides lazy access to object selections. For synchronous access where all
     * selections are pre-resolved, use [getObjectValue] instead.
     */
    val objectValue: T

    /**
     * Returns a synchronously-accessible version of [objectValue] where all selections have
     * been eagerly resolved.
     *
     * Unlike [objectValue] which resolves selections lazily on access, this method awaits
     * the resolution of all selections upfront, returning a [T] that can be accessed
     * synchronously without suspending.
     *
     * Use this when you need to ensure all object value data is available before proceeding,
     * or when passing the object value to non-suspending code.
     */
    @ExperimentalApi
    suspend fun getObjectValue(): T
}
