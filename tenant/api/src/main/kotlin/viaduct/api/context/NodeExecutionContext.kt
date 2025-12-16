package viaduct.api.context

import viaduct.api.globalid.GlobalID
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.apiannotations.StableApi

/**
 * An ExecutionContext provided to [Node] resolvers
 */
@StableApi
interface NodeExecutionContext<T : NodeObject> : ResolverExecutionContext {
    /**
     * ID of the node that is being resolved
     */
    val id: GlobalID<T>

    /**
     * The [SelectionSet] for [T] that the caller provided
     */
    fun selections(): SelectionSet<T>
}
