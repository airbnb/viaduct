package viaduct.api.context

import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.StableApi

/**
 * Base [ExecutionContext] interface for mutation and non-mutation field resolvers
 */
@StableApi
interface BaseFieldExecutionContext<
    Q : Query,
    A : Arguments,
    O : CompositeOutput
> : ResolverExecutionContext<Q> {
    /**
     * A value of [Q], with any (and only) selections from [viaduct.api.Resolver.queryValueFragment]
     * populated.
     * Attempting to access fields not declared in [viaduct.api.Resolver.queryValueFragment] will
     * throw a runtime exception.
     *
     * This property provides lazy access to query selections. For synchronous access where all
     * selections are pre-resolved, use [getQueryValue] instead.
     */
    val queryValue: Q

    /**
     * Returns a synchronously-accessible version of [queryValue] where all selections have
     * been eagerly resolved.
     *
     * Unlike [queryValue] which resolves selections lazily on access, this method awaits
     * the resolution of all selections upfront, returning a [Q] that can be accessed
     * synchronously without suspending.
     *
     * Use this when you need to ensure all query value data is available before proceeding,
     * or when passing the query value to non-suspending code.
     */
    @ExperimentalApi
    suspend fun getQueryValue(): Q

    /**
     * The value of any [A] arguments that were provided by the caller of this
     * resolver. If this field does not take arguments, this is [Arguments.NoArguments].
     */
    val arguments: A

    /**
     * The [SelectionSet] for [O] that the caller provided. If this field does not have a
     * selection set (i.e. it has a scalar or enum type), this returns [SelectionSet.NoSelections].
     */
    fun selections(): SelectionSet<O>
}
