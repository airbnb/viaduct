package viaduct.api.testing.spec

import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockMutationFieldExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.testing.spec.base.BaseFieldSpec
import viaduct.api.testing.spec.base.buildContextMutationMap
import viaduct.api.testing.types.NullQuery
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Typed input carrier for ResolverTestBase.runMutationFieldResolver. See
 * FieldResolverSpec for the general pattern. `contextMutationValues` mirrors
 * `contextQueryValues` but for `ctx.mutation()` results and accepts
 * [viaduct.api.testing.types.MutationForSelection]-wrapped values for per-selection results.
 *
 * Mirrors [MutationFieldExecutionContext] in the hierarchy.
 */
@ExperimentalApi
class MutationResolverSpec<Q : Query, M : Mutation, A : Arguments> : BaseFieldSpec<Q, A>() {
    var contextMutationValues: List<Mutation> = emptyList()

    @OptIn(InternalApi::class)
    fun createContext(
        resolverClass: Class<*>,
        internalContext: InternalContext,
        selectionSetFactory: SelectionSetFactory,
    ): MutationFieldExecutionContext<*, *, *, *> {
        val ctxKClass = getResolverContextKClass<MutationFieldExecutionContext<*, *, *, *>>(resolverClass)
        val queryResultsMap = buildQueryResultsMap(internalContext, selectionSetFactory)
        val mutationResultsMap = buildContextMutationMap(contextMutationValues, internalContext, selectionSetFactory)

        @Suppress("UNCHECKED_CAST")
        val innerCtx = MockMutationFieldExecutionContext<Query, Mutation, Arguments, CompositeOutput>(
            queryValue = queryValue ?: NullQuery,
            arguments = arguments ?: Arguments.NoArguments,
            requestContext = requestContext,
            selectionsValue = selections as SelectionSet<CompositeOutput>,
            internalContext = internalContext,
            queryResults = queryResultsMap,
            mutationResults = mutationResultsMap,
            selectionSetFactory = selectionSetFactory,
            referenceSpy = referenceSpy,
        )

        return ctxKClass.wrapOrReturn(innerCtx)
    }
}
