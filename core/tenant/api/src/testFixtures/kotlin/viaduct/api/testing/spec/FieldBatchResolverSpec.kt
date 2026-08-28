package viaduct.api.testing.spec

import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockFieldExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.testing.spec.base.BaseResolverSpec
import viaduct.api.testing.types.NullQuery
import viaduct.api.types.Arguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Typed input carrier for ResolverTestBase.runFieldBatchResolver. Carries the same
 * fields as FieldResolverSpec except `objectValue`/`queryValue` are replaced by
 * typed lists, and `arguments` is absent (batch fields with arguments are not yet
 * supported by the framework).
 */
@ExperimentalApi
class FieldBatchResolverSpec<O : Object, Q : Query> : BaseResolverSpec() {
    var objectValues: List<O> = emptyList()
    var queryValues: List<Q>? = null
    var selections: SelectionSet<*> = SelectionSet.NoSelections

    @OptIn(InternalApi::class)
    fun createContexts(
        resolverClass: Class<*>,
        internalContext: InternalContext,
        selectionSetFactory: SelectionSetFactory,
    ): List<FieldExecutionContext<*, *, *, *>> {
        val ctxKClass = getResolverContextKClass<FieldExecutionContext<*, *, *, *>>(resolverClass)

        @Suppress("UNCHECKED_CAST")
        val resolvedQueryValues = queryValues ?: objectValues.map { NullQuery as Q }
        require(objectValues.size == resolvedQueryValues.size) {
            "objectValues and queryValues must have the same size: objectValues.size=${objectValues.size}, queryValues.size=${resolvedQueryValues.size}"
        }

        val queryResultsMap = buildQueryResultsMap(internalContext, selectionSetFactory)

        return objectValues.zip(resolvedQueryValues) { obj, query ->
            val innerCtx = MockFieldExecutionContext(
                objectValue = obj,
                queryValue = query,
                arguments = Arguments.NoArguments,
                requestContext = requestContext,
                selectionsValue = selections,
                internalContext = internalContext,
                queryResults = queryResultsMap,
                selectionSetFactory = selectionSetFactory,
                rootFieldRefResults = buildRootFieldRefResults(),
                referenceSpy = referenceSpy,
            )
            ctxKClass.wrapOrReturn(innerCtx)
        }
    }
}
