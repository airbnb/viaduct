package viaduct.tenant.runtime.context

import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.NodeReferenceGRTFactory
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/**
 * Implementation for ExecutionContext, used to delegate common implementations for field and Node execution contexts.
 */
class ResolverExecutionContextImpl(
    internal: InternalContext,
    requestContext: Any?,
    private val queryLoader: SelectionsLoader<Query>,
    private val selectionSetFactory: SelectionSetFactory,
    private val nodeReferenceFactory: NodeReferenceGRTFactory
) : ExecutionContext by ExecutionContextImpl(internal, requestContext), InternalContext by internal, ResolverExecutionContext {
    override suspend fun <T : Query> query(selections: SelectionSet<T>) = queryLoader.load(this, selections)

    override fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ) = selectionSetFactory.selectionsOn(type, selections, variables)

    override fun <T : NodeObject> nodeFor(id: GlobalID<T>) = nodeReferenceFactory.nodeFor(id, this)

    override fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String,
    ) = globalIDCodec.serialize(globalIDFor(type, internalID))
}
