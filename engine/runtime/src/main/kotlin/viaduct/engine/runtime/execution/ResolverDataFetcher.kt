package viaduct.engine.runtime.execution

import graphql.language.OperationDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.engineExecutionContext
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.CheckerProxyEngineObjectData
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.ProxyEngineObjectData
import viaduct.engine.runtime.context.findLocalContextForType
import viaduct.engine.runtime.execution.FieldExecutionHelpers.resolveRSSVariables

class ResolverDataFetcher(
    internal val typeName: String,
    internal val fieldName: String,
    private val fieldResolverDispatcher: FieldResolverDispatcher,
    private val checkerDispatcher: CheckerDispatcher?,
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop
) : DataFetcher<CompletableFuture<*>> {
    companion object {
        /**
         * Data class to hold the resolver and query proxy engine object data.
         * This is used to resolve the field in the resolver executor.
         */
        private data class EngineObjectData(
            val fieldResolverDispatcherEOD: ProxyEngineObjectData,
            val queryProxyEOD: ProxyEngineObjectData
        )

        /**
         * Data class to hold the results of the engine execution.
         */
        private data class EngineResults(
            val parentResult: ObjectEngineResult,
            val queryResult: ObjectEngineResult
        )
    }

    override fun get(environment: DataFetchingEnvironment): CompletableFuture<*> =
        coroutineInterop.scopedFuture {
            resolve(environment)
        }

    private suspend fun resolve(environment: DataFetchingEnvironment): Any? {
        val engineResults = getEngineResults(environment)

        val engineExecutionContext = environment.engineExecutionContext
        val localExecutionContext = engineExecutionContext.copy(
            dataFetchingEnvironment = environment
        )

        val (objectValueEOD, queryValueEOD) = getFieldResolverDispatcherEOD(localExecutionContext, environment, engineResults)
        if (localExecutionContext.executeAccessChecksInModstrat) {
            return resolveField(environment, objectValueEOD, queryValueEOD, localExecutionContext)
        }

        // Before modern access check is fully implemented, all modern fields will not be using
        // modern execution strategy, thus we still need to execute access check here.
        // TODO: Checker execution below will be removed from data fetcher, once modern strategy
        //  implementation is done.
        // --------- Execute access checks in ResolverDataFetcher ---------------
        // If there is no checker, just resolve the field
        if (checkerDispatcher == null) {
            return resolveField(environment, objectValueEOD, queryValueEOD, localExecutionContext)
        }

        val checkerProxyEODMap = getCheckerProxyEODMap(environment, engineResults.parentResult)
        return when (environment.operationDefinition.operation) {
            // for query, execute checker and resolve field in parallel
            OperationDefinition.Operation.QUERY -> {
                supervisorScope {
                    val checkAsync = async {
                        checkerDispatcher.execute(
                            environment.arguments,
                            checkerProxyEODMap,
                            localExecutionContext,
                            CheckerExecutor.CheckerType.FIELD
                        )
                    }
                    runCatching {
                        resolveField(environment, objectValueEOD, queryValueEOD, localExecutionContext)
                    }.onSuccess {
                        val checkerResult = checkAsync.await()
                        checkerResult.asError?.let { throw it.error }
                    }.getOrThrow()
                }
            }
            // for mutation, execute checker then resolve field synchronously
            OperationDefinition.Operation.MUTATION -> {
                val checkerResult = checkerDispatcher.execute(
                    environment.arguments,
                    checkerProxyEODMap,
                    localExecutionContext,
                    CheckerExecutor.CheckerType.FIELD
                )
                checkerResult.asError?.let { throw it.error }
                resolveField(environment, objectValueEOD, queryValueEOD, localExecutionContext)
            }

            else -> throw NotImplementedError("Unsupported operation: ${environment.operationDefinition.operation}")
        }
    }

    private suspend fun getFieldResolverDispatcherEOD(
        localExecutionContext: EngineExecutionContext,
        environment: DataFetchingEnvironment,
        engineResults: EngineResults,
    ): EngineObjectData {
        val selectionSetFactory = localExecutionContext.rawSelectionSetFactory
        val objectSelectionSet = fieldResolverDispatcher.objectSelectionSet?.let { rss ->
            val variables = resolveRSSVariables(
                rss = rss,
                arguments = environment.arguments,
                currentEngineData = engineResults.parentResult,
                queryEngineData = engineResults.queryResult,
                engineExecutionContext = localExecutionContext,
                environment.graphQlContext,
                environment.locale
            )
            selectionSetFactory.rawSelectionSet(rss.selections, variables.toMap())
        }
        val fieldResolverDispatcherEOD = ProxyEngineObjectData(
            engineResults.parentResult,
            "add it to @Resolver's objectValueFragment before accessing it via Context.objectValue",
            objectSelectionSet
        )

        val querySelectionSet = fieldResolverDispatcher.querySelectionSet?.let { rss ->
            val variables = resolveRSSVariables(
                rss = rss,
                arguments = environment.arguments,
                currentEngineData = engineResults.queryResult,
                queryEngineData = engineResults.queryResult,
                engineExecutionContext = localExecutionContext,
                environment.graphQlContext,
                environment.locale
            )
            selectionSetFactory.rawSelectionSet(rss.selections, variables.toMap())
        }

        val queryProxyEOD = ProxyEngineObjectData(
            engineResults.queryResult,
            "add it to @Resolver's queryValueFragment before accessing it via Context.queryValue",
            querySelectionSet
        )

        return EngineObjectData(fieldResolverDispatcherEOD, queryProxyEOD)
    }

    private suspend fun resolveField(
        environment: DataFetchingEnvironment,
        fieldResolverDispatcherEOD: ProxyEngineObjectData,
        resolverQueryProxyEOD: ProxyEngineObjectData,
        engineExecutionContext: EngineExecutionContext
    ) = fieldResolverDispatcher.resolve(
        environment.arguments,
        fieldResolverDispatcherEOD,
        resolverQueryProxyEOD,
        engineExecutionContext.rawSelectionSetFactory.rawSelectionSet(environment),
        engineExecutionContext
    )

    private fun getEngineResults(environment: DataFetchingEnvironment): EngineResults {
        val engineLoaderContext = environment.findLocalContextForType<EngineResultLocalContext>()
        val queryEngineResult = engineLoaderContext.queryEngineResult
        val parentEngineResult = engineLoaderContext.parentEngineResult
        assert(parentEngineResult.graphQLObjectType.name == typeName)
        return EngineResults(parentEngineResult, queryEngineResult)
    }

    /**
     * Get checker proxyEOD from engine result. This supports both old and new engine.
     * Note if `shouldUseModernExecutionStrategy(...)` returns false, it means the engine result
     * is loaded from old engine, hence only builds proxyEOD from the first checker selection set.
     */
    private fun getCheckerProxyEODMap(
        environment: DataFetchingEnvironment,
        engineResult: ObjectEngineResult
    ): Map<String, CheckerProxyEngineObjectData> {
        check(checkerDispatcher != null) {
            "Checker executor should not be null when getting checker proxyEOD map."
        }
        val checkerSelectionSetMap = checkerDispatcher.requiredSelectionSets

        val selectionSetFactory = environment.engineExecutionContext.rawSelectionSetFactory
        return checkerSelectionSetMap.mapValues { (_, rss) ->
            val selectionSet = rss?.let { selectionSetFactory.rawSelectionSet(rss.selections, emptyMap()) }
            CheckerProxyEngineObjectData(
                engineResult,
                "missing from checker RSS",
                selectionSet
            )
        }
    }
}
