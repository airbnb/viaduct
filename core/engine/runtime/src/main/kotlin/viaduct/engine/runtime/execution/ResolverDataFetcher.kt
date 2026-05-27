@file:Suppress("DEPRECATION") // CoroutineInterop retained for Airbnb

package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.withContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData as EngineObjectDataApi
import viaduct.engine.api.instrumentation.ViaductTenantNameContext
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.dispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextExtensions.selectiveOERKeysEnabled
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.IsResolverSelective
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ProxyEngineObjectData
import viaduct.engine.runtime.SyncEngineObjectDataFactory
import viaduct.engine.runtime.context.findLocalContextForType
import viaduct.engine.runtime.dfe.engineExecutionContext
import viaduct.engine.runtime.execution.FieldExecutionHelpers.resolveRSSVariables

class ResolverDataFetcher(
    internal val typeName: String,
    internal val fieldName: String,
    private val fieldResolverDispatcher: FieldResolverDispatcher,
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
    private val tenantNameResolver: TenantNameResolver = TenantNameResolver(),
) : DataFetcher<CompletableFuture<*>> {
    companion object {
        /**
         * Data class to hold the resolver and query proxy engine object data.
         * This is used to resolve the field in the resolver executor.
         */
        private data class EngineObjectData(
            val objectValue: ProxyEngineObjectData,
            val queryValue: ProxyEngineObjectData,
            val syncObjectValueGetter: suspend () -> EngineObjectDataApi.Sync,
            val syncQueryValueGetter: suspend () -> EngineObjectDataApi.Sync,
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
        val tenantName = tenantNameResolver.resolve(typeName, fieldName)
        return withContext(ViaductTenantNameContext.asCoroutineContext(ViaductTenantNameContext(tenantName))) {
            resolveWithTenantContext(environment)
        }
    }

    private suspend fun resolveWithTenantContext(environment: DataFetchingEnvironment): Any? {
        val engineResults = getEngineResults(environment)

        val resolverExecutionContext = environment.engineExecutionContext.copy(
            dataFetchingEnvironment = environment
        )
        val engineObjectData = getFieldResolverDispatcherEOD(resolverExecutionContext, environment, engineResults)
        return resolveField(environment, engineObjectData, resolverExecutionContext)
    }

    /**
     * Builds the objectValue and queryValue proxies for this resolver.
     * It also picks the selection set each proxy should use for OER key lookups.
     */
    private suspend fun getFieldResolverDispatcherEOD(
        localExecutionContext: EngineExecutionContext,
        environment: DataFetchingEnvironment,
        engineResults: EngineResults,
    ): EngineObjectData {
        val selectionSetFactory = localExecutionContext.engineSelectionSetFactory
        val isResolverSelective = IsResolverSelective.fromRegistry(
            localExecutionContext.dispatcherRegistry,
            localExecutionContext.selectiveOERKeysEnabled,
        )

        val objectErrorMessage =
            "add it to @Resolver's objectValueFragment before accessing it via Context.objectValue"
        val objectRss = fieldResolverDispatcher.objectSelectionSet
        val objectVariables = objectRss?.let { rss ->
            resolveRSSVariables(
                rss = rss,
                arguments = environment.arguments,
                currentEngineData = engineResults.parentResult,
                queryEngineData = engineResults.queryResult,
                engineExecutionContext = localExecutionContext,
                environment.graphQlContext,
                environment.locale,
            )
        }
        val objectEngineSelectionSet = objectVariables?.let { variables ->
            selectionSetFactory.engineSelectionSet(objectRss.selections, variables.toMap())
        }
        val objectOERSelections: ObjectEngineResult.Selections? = objectVariables?.let { variables ->
            FieldExecutionHelpers.createOERSelections(
                objectRss,
                variables,
                localExecutionContext,
            )
        }
        val objectValue = ProxyEngineObjectData(
            engineResults.parentResult,
            objectErrorMessage,
            objectEngineSelectionSet,
            isResolverSelective,
            objectOERSelections,
        )
        val syncObjectValueGetter: suspend () -> EngineObjectDataApi.Sync = {
            SyncEngineObjectDataFactory.resolve(
                engineResults.parentResult,
                objectErrorMessage,
                objectEngineSelectionSet,
                parentPath = environment.executionStepInfo.path.parent,
                isResolverSelective = isResolverSelective,
                selections = objectOERSelections,
            )
        }

        val queryErrorMessage =
            "add it to @Resolver's queryValueFragment before accessing it via Context.queryValue"
        val queryRss = fieldResolverDispatcher.querySelectionSet
        // queryValueFragment variables may still source values from the resolver's parent object,
        // e.g. via fromObjectField on a non-root resolver.
        val queryVariables = queryRss?.let { rss ->
            resolveRSSVariables(
                rss = rss,
                arguments = environment.arguments,
                currentEngineData = engineResults.parentResult,
                queryEngineData = engineResults.queryResult,
                engineExecutionContext = localExecutionContext,
                environment.graphQlContext,
                environment.locale,
            )
        }
        val queryEngineSelectionSet = queryVariables?.let { variables ->
            selectionSetFactory.engineSelectionSet(queryRss.selections, variables.toMap())
        }
        val queryOERSelections: ObjectEngineResult.Selections? = queryVariables?.let { variables ->
            FieldExecutionHelpers.createOERSelections(
                queryRss,
                variables,
                localExecutionContext,
            )
        }
        val queryValue = ProxyEngineObjectData(
            engineResults.queryResult,
            queryErrorMessage,
            queryEngineSelectionSet,
            isResolverSelective,
            queryOERSelections,
        )
        val syncQueryValueGetter: suspend () -> EngineObjectDataApi.Sync = {
            SyncEngineObjectDataFactory.resolve(
                engineResults.queryResult,
                queryErrorMessage,
                queryEngineSelectionSet,
                parentPath = ResultPath.rootPath(),
                isResolverSelective = isResolverSelective,
                selections = queryOERSelections,
            )
        }

        return EngineObjectData(
            objectValue = objectValue,
            queryValue = queryValue,
            syncObjectValueGetter = syncObjectValueGetter,
            syncQueryValueGetter = syncQueryValueGetter,
        )
    }

    /** Calls the tenant resolver with the proxy values and the current field selection set. */
    private suspend fun resolveField(
        environment: DataFetchingEnvironment,
        engineObjectData: EngineObjectData,
        engineExecutionContext: EngineExecutionContext
    ) = fieldResolverDispatcher.resolve(
        environment.arguments,
        engineObjectData.objectValue,
        engineObjectData.queryValue,
        engineObjectData.syncObjectValueGetter,
        engineObjectData.syncQueryValueGetter,
        engineExecutionContext.engineSelectionSetFactory.engineSelectionSet(environment),
        engineExecutionContext
    )

    /** Gets the parent result and the root query result for this resolver call. */
    private fun getEngineResults(environment: DataFetchingEnvironment): EngineResults {
        val engineLoaderContext = environment.findLocalContextForType<EngineResultLocalContext>()
        val queryEngineResult = engineLoaderContext.queryEngineResult
        val parentEngineResult = engineLoaderContext.parentEngineResult
        assert(parentEngineResult.type.name == typeName)
        return EngineResults(parentEngineResult, queryEngineResult)
    }
}
