package viaduct.tenant.runtime.context

import viaduct.api.context.NodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject

class NodeExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    private val selections: SelectionSet<NodeObject>,
    override val requestContext: Any?,
    override val id: GlobalID<NodeObject>,
) : NodeExecutionContext<NodeObject>, ResolverExecutionContextImpl(baseData, engineExecutionContextWrapper) {
    override fun selections() = selections
}
