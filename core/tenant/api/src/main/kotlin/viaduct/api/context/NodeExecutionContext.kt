package viaduct.api.context

import viaduct.api.globalid.GlobalID
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.apiannotations.StableApi

/**
 * Base ExecutionContext for [Node] resolvers without access to selections.
 *
 * This is the default context used by generated node resolver base classes.
 * Non-selective resolvers (the default) receive this context, which does not
 * expose the `selections()` method.
 *
 * @see SelectiveNodeExecutionContext for the extended context with `selections()` access
 * @see viaduct.api.SelectiveResolver marker interface for selective resolvers
 */
@StableApi
interface NodeExecutionContext<R : NodeObject> : ResolverExecutionContext<Query> {
    /**
     * ID of the node that is being resolved
     */
    val id: GlobalID<R>
}

/**
 * Extended ExecutionContext for [Node] resolvers with access to selections.
 *
 * This interface extends [NodeExecutionContext] and adds the `selections()`
 * method. Only resolvers implementing [viaduct.api.SelectiveResolver] should use
 * this context to access the selection set.
 *
 * @see NodeExecutionContext for the base context without `selections()`
 * @see viaduct.api.SelectiveResolver marker interface for selective resolvers
 */
@StableApi
interface SelectiveNodeExecutionContext<R : NodeObject> : NodeExecutionContext<R> {
    /**
     * The [SelectionSet] for [R] that the caller provided
     */
    fun selections(): SelectionSet<R>
}
