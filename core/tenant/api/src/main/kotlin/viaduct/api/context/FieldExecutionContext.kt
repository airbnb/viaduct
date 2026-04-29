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
interface FieldExecutionContext<O : Object, Q : Query, A : Arguments, R : CompositeOutput> : BaseFieldExecutionContext<Q, A, R> {
    /**
     * Returns a synchronously-accessible version of the object value where all selections have
     * been eagerly resolved.
     *
     * All selections declared in [viaduct.api.Resolver.objectValueFragment] are available
     * synchronously without suspending.
     */
    suspend fun getObjectValue(): O
}
