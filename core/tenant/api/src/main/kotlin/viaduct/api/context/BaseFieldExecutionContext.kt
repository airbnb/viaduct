package viaduct.api.context

import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Query
import viaduct.apiannotations.StableApi

/**
 * Base [ExecutionContext] interface for mutation and non-mutation field resolvers
 */
@StableApi
interface BaseFieldExecutionContext<
    Q : Query,
    A : Arguments,
    R : CompositeOutput
> : ResolverExecutionContext<Q> {
    /**
     * Returns a synchronously-accessible version of the query value where all selections have
     * been eagerly resolved.
     *
     * All selections declared in [viaduct.api.Resolver.queryValueFragment] are available
     * synchronously without suspending.
     */
    suspend fun getQueryValue(): Q

    /**
     * The value of any [A] arguments that were provided by the caller of this
     * resolver. If this field does not have arguments, this is [Arguments.NoArguments].
     */
    val arguments: A
}

@StableApi
interface SelectiveFieldExecutionContext<R : CompositeOutput> {
    /**
     * The [SelectionSet] for [R] that the caller provided. If this field does not have a
     * selection set (i.e. it has a scalar or enum type), this returns [SelectionSet.NoSelections].
     */
    fun selections(): SelectionSet<R>
}
