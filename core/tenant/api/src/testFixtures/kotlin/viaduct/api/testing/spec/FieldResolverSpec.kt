package viaduct.api.testing.spec

import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockConnectionFieldExecutionContext
import viaduct.api.mocks.MockFieldExecutionContext
import viaduct.api.resolver.Resolver
import viaduct.api.select.SelectionSet
import viaduct.api.testing.spec.base.BaseFieldSpec
import viaduct.api.testing.types.NullObject
import viaduct.api.testing.types.NullQuery
import viaduct.api.types.Arguments
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Typed input carrier for ResolverTestBase.runFieldResolver. The lambda receiver is
 * parameterized on the resolver's `O`/`Q`/`A`, so the IDE enforces
 * `objectValue`/`queryValue`/`arguments` types as you type.
 *
 * `contextQueryValues` accepts plain [Query] objects (falling back to NoSelections) or
 * [viaduct.api.testing.types.QueryForSelection]-wrapped values when pre-baking results per selection set.
 *
 * Mirrors [FieldExecutionContext] in the hierarchy.
 */
@ExperimentalApi
class FieldResolverSpec<O : Object, Q : Query, A : Arguments> : BaseFieldSpec<Q, A>() {
    var objectValue: O? = null

    @OptIn(InternalApi::class)
    fun createContext(
        resolverClass: Class<*>,
        internalContext: InternalContext,
        selectionSetFactory: SelectionSetFactory,
    ): FieldExecutionContext<*, *, *, *> {
        val resolverAnnotation = resolverClass.getAnnotation(Resolver::class.java)
        if (resolverAnnotation != null) {
            if (resolverAnnotation.objectValueFragment.isNotEmpty()) {
                requireNotNull(objectValue) {
                    "Resolver (${resolverClass.name}) has @Resolver(objectValueFragment = ...) but objectValue was not set in inputs"
                }
            }
            if (resolverAnnotation.queryValueFragment.isNotEmpty()) {
                requireNotNull(queryValue) {
                    "Resolver (${resolverClass.name}) has @Resolver(queryValueFragment = ...) but queryValue was not set in inputs"
                }
            }
        }

        val ctxKClass = getResolverContextKClass<FieldExecutionContext<*, *, *, *>>(resolverClass)
        val queryResultsMap = buildQueryResultsMap(internalContext, selectionSetFactory)

        val isConnectionContext = ConnectionFieldExecutionContext::class.java.isAssignableFrom(ctxKClass.java)
        val innerCtx: FieldExecutionContext<*, *, *, *> = if (isConnectionContext) {
            val connectionArguments = arguments as? ConnectionArguments
                ?: throw IllegalArgumentException(
                    "Resolver (${ctxKClass.qualifiedName}) uses ConnectionFieldExecutionContext but the provided " +
                        "arguments do not implement ConnectionArguments. " +
                        "Pass a ConnectionArguments instance to runFieldResolver()."
                )
            @Suppress("UNCHECKED_CAST")
            MockConnectionFieldExecutionContext<Object, Query, ConnectionArguments, Connection<*, *>>(
                objectValue = objectValue ?: NullObject,
                queryValue = queryValue ?: NullQuery,
                arguments = connectionArguments,
                requestContext = requestContext,
                selectionsValue = selections as SelectionSet<Connection<*, *>>,
                internalContext = internalContext,
                queryResults = queryResultsMap,
                selectionSetFactory = selectionSetFactory,
                referenceSpy = referenceSpy,
            )
        } else {
            MockFieldExecutionContext(
                objectValue = objectValue ?: NullObject,
                queryValue = queryValue ?: NullQuery,
                arguments = arguments ?: Arguments.NoArguments,
                requestContext = requestContext,
                selectionsValue = selections,
                internalContext = internalContext,
                queryResults = queryResultsMap,
                selectionSetFactory = selectionSetFactory,
                referenceSpy = referenceSpy,
            )
        }

        return ctxKClass.wrapOrReturn(innerCtx)
    }
}
