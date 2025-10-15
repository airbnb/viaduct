package viaduct.tenant.runtime.context

import viaduct.api.context.NodeExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.internal
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject

class NodeExecutionContextImpl<T : NodeObject>(
    private val executionContext: ResolverExecutionContext,
    override val id: GlobalID<T>,
    private val selections: SelectionSet<T>,
) : NodeExecutionContext<T>, ResolverExecutionContext by executionContext, InternalContext by executionContext.internal {
    override fun selections() = selections
}
