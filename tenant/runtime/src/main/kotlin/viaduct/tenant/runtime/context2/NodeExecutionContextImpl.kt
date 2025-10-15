package viaduct.tenant.runtime.context2

import viaduct.api.context.NodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineExecutionContext

class NodeExecutionContextImpl(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    private val selections: SelectionSet<NodeObject>,
    override val requestContext: Any?,
    override val id: GlobalID<NodeObject>,
) : NodeExecutionContext<NodeObject>, ResolverExecutionContextImpl(baseData, engineExecutionContextWrapper) {
    constructor(
        baseData: InternalContext,
        engineExecutionContext: EngineExecutionContext,
        selections: SelectionSet<NodeObject>,
        requestContext: Any?,
        id: GlobalID<NodeObject>,
    ) : this(baseData, EngineExecutionContextWrapperImpl(engineExecutionContext), selections, requestContext, id)

    override fun selections() = selections
}
