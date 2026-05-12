package viaduct.api.testing.spec

import viaduct.api.context.NodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockNodeExecutionContext
import viaduct.api.testing.spec.base.BaseNodeSpec
import viaduct.api.types.NodeObject
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Typed input carrier for ResolverTestBase.runNodeBatchResolver.
 */
@ExperimentalApi
class NodeBatchResolverSpec<T : NodeObject> : BaseNodeSpec<T>() {
    var ids: List<GlobalID<T>> = emptyList()

    @OptIn(InternalApi::class)
    fun createContexts(
        resolverClass: Class<*>,
        internalContext: InternalContext,
        selectionSetFactory: SelectionSetFactory,
    ): List<NodeExecutionContext<T>> {
        val ctxKClass = getNodeContextKClass(resolverClass)
        val queryResultsMap = buildQueryResultsMap(internalContext, selectionSetFactory)

        return ids.map { id ->
            val innerCtx = MockNodeExecutionContext(
                id = id,
                requestContext = requestContext,
                selectionsValue = selections,
                internalContext = internalContext,
                queryResults = queryResultsMap,
                selectionSetFactory = selectionSetFactory,
            )
            ctxKClass.wrapOrReturn(innerCtx)
        }
    }
}
