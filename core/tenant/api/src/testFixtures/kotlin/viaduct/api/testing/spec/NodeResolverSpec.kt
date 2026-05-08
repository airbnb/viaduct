package viaduct.api.testing.spec

import io.mockk.mockk
import viaduct.api.context.NodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockNodeExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.testing.spec.base.BaseNodeSpec
import viaduct.api.types.NodeObject
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Typed input carrier for ResolverTestBase.runNodeResolver.
 */
@ExperimentalApi
class NodeResolverSpec<T : NodeObject> : BaseNodeSpec<T>() {
    var id: GlobalID<T>? = null

    @OptIn(InternalApi::class)
    fun createContext(
        resolverClass: Class<*>,
        internalContext: InternalContext,
        selectionSetFactory: SelectionSetFactory,
    ): NodeExecutionContext<T> {
        val resolvedId = requireNotNull(id) { "NodeResolverSpec.id must be set" }
        val ctxKClass = getNodeContextKClass(resolverClass)
        val queryResultsMap = buildQueryResultsMap(internalContext, selectionSetFactory)

        val innerCtx = MockNodeExecutionContext(
            id = resolvedId,
            requestContext = requestContext,
            selectionsValue = selections ?: mockk<SelectionSet<T>>(),
            internalContext = internalContext,
            queryResults = queryResultsMap,
            selectionSetFactory = selectionSetFactory,
        )

        return ctxKClass.wrapOrReturn(innerCtx)
    }
}
