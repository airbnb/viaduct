package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.withContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData as EngineObjectDataApi
import viaduct.engine.api.engineExecutionContext
import viaduct.engine.api.instrumentation.ViaductTenantNameContext
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ProxyEngineObjectData
import viaduct.engine.runtime.SyncEngineObjectDataFactory
import viaduct.engine.runtime.context.findLocalContextForType
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

        val engineExecutionContext = environment.engineExecutionContext
        val localExecutionContext = engineExecutionContext.copy(
            dataFetchingEnvironment = environment
        )

        val engineObjectData = getFieldResolverDispatcherEOD(localExecutionContext, environment, engineResults)
        return resolveField(environment, engineObjectData, localExecutionContext)
    }

    private suspend fun getFieldResolverDispatcherEOD(
        localExecutionContext: EngineExecutionContext,
        environment: DataFetchingEnvironment,
        engineResults: EngineResults,
    ): EngineObjectData {
        val selectionSetFactory = localExecutionContext.engineSelectionSetFactory

        val objectErrorMessage =
            "add it to @Resolver's objectValueFragment before accessing it via Context.objectValue"
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
            selectionSetFactory.engineSelectionSet(rss.selections, variables.toMap())
        }
        val objectValue = ProxyEngineObjectData(
            engineResults.parentResult,
            objectErrorMessage,
            objectSelectionSet
        )
        val syncObjectValueGetter: suspend () -> EngineObjectDataApi.Sync = {
            SyncEngineObjectDataFactory.resolve(
                engineResults.parentResult,
                objectErrorMessage,
                objectSelectionSet,
                parentPath = environment.executionStepInfo.path.parent
            )
        }

        val queryErrorMessage =
            "add it to @Resolver's queryValueFragment before accessing it via Context.queryValue"
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
            selectionSetFactory.engineSelectionSet(rss.selections, variables.toMap())
        }
        val queryValue = ProxyEngineObjectData(
            engineResults.queryResult,
            queryErrorMessage,
            querySelectionSet
        )
        val syncQueryValueGetter: suspend () -> EngineObjectDataApi.Sync = {
            SyncEngineObjectDataFactory.resolve(
                engineResults.queryResult,
                queryErrorMessage,
                querySelectionSet,
                parentPath = ResultPath.rootPath()
            )
        }

        return EngineObjectData(objectValue, queryValue, syncObjectValueGetter, syncQueryValueGetter)
    }

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

    private fun getEngineResults(environment: DataFetchingEnvironment): EngineResults {
        val engineLoaderContext = environment.findLocalContextForType<EngineResultLocalContext>()
        val queryEngineResult = engineLoaderContext.queryEngineResult
        val parentEngineResult = engineLoaderContext.parentEngineResult
        assert(parentEngineResult.type.name == typeName)
        return EngineResults(parentEngineResult, queryEngineResult)
    }
}
