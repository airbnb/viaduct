package viaduct.api.internal.select

import viaduct.api.context.ExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.InternalApi

@InternalApi
interface SelectionsLoader<T : CompositeOutput> {
    /**
     * Load the provided [SelectionSet] and return an instance of the
     * selected type with selected fields populated
     */
    suspend fun <U : T> load(
        ctx: ExecutionContext,
        selections: SelectionSet<U>
    ): U

    interface Factory {
        /** create a [SelectionsLoader] for [Query] types */
        fun forQuery(resolverId: String): SelectionsLoader<Query>

        /** create a [SelectionsLoader] for [Mutation] types */
        fun forMutation(resolverId: String): SelectionsLoader<Mutation>
    }

    companion object {
        /** Create a [SelectionsLoader] that always returns a constant value */
        fun <T : Object, U : T> const(u: U): SelectionsLoader<T> =
            object : SelectionsLoader<T> {
                @Suppress("UNCHECKED_CAST")
                override suspend fun <U : T> load(
                    ctx: ExecutionContext,
                    selections: SelectionSet<U>
                ): U = u as U
            }
    }
}
