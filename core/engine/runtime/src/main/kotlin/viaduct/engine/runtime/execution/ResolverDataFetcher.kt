@file:Suppress("DEPRECATION") // CoroutineInterop retained for Airbnb

package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.withContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.instrumentation.ViaductTenantNameContext
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineObjectDataFactory
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.SyncEngineObjectDataFactory
import viaduct.engine.runtime.context.findLocalContextForType
import viaduct.engine.runtime.dfe.engineExecutionContext
import viaduct.engine.runtime.execution.FieldExecutionHelpers.engineSelectionSet
import viaduct.engine.runtime.execution.FieldExecutionHelpers.resolveRSSVariables

class ResolverDataFetcher(
    internal val typeName: String,
    internal val fieldName: String,
    private val fieldResolverDispatcher: FieldResolverDispatcher,
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
    private val tenantNameResolver: TenantNameResolver = TenantNameResolver(),
) : DataFetcher<CompletableFuture<*>> {
    companion object {
        private data class Factories(
            val objectValueFactory: EngineObjectDataFactory,
            val queryValueFactory: EngineObjectDataFactory,
        )

        private data class EngineResults(
            val currentObjectResult: ObjectEngineResult,
            val queryResult: ObjectEngineResult,
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
        val factories = buildFactories(resolverExecutionContext, environment, engineResults)
        return resolveField(environment, factories, resolverExecutionContext)
    }

    private suspend fun buildFactories(
        localExecutionContext: EngineExecutionContext,
        environment: DataFetchingEnvironment,
        engineResults: EngineResults,
    ): Factories {
        val selectionSetFactory = localExecutionContext.engineSelectionSetFactory

        val objectRss = fieldResolverDispatcher.objectSelectionSet
        val objectEngineSelectionSet = objectRss?.let { rss ->
            val queryPlan = FieldExecutionHelpers.findRssQueryPlan(rss, localExecutionContext)
            val variables = resolveRSSVariables(
                arguments = environment.arguments,
                currentEngineData = engineResults.currentObjectResult,
                queryEngineData = engineResults.queryResult,
                engineExecutionContext = localExecutionContext,
                environment.graphQlContext,
                environment.locale,
                queryPlan = queryPlan,
            )
            selectionSetFactory.engineSelectionSet(rss.selections, variables.toMap())
        }
        val objectParentPath = environment.executionStepInfo.path.parent
        val objectErrorMessage = "add it to @Resolver's objectValueFragment before accessing it via Context.objectValue"

        val objectValueFactory = EngineObjectDataFactory { instrumentationContext ->
            SyncEngineObjectDataFactory.resolve(
                engineResults.currentObjectResult,
                objectErrorMessage,
                objectEngineSelectionSet,
                parentPath = objectParentPath,
                instrumentationContext = instrumentationContext,
            )
        }

        val queryRss = fieldResolverDispatcher.querySelectionSet
        val queryEngineSelectionSet = queryRss?.let { rss ->
            val queryPlan = FieldExecutionHelpers.findRssQueryPlan(rss, localExecutionContext)
            val variables = resolveRSSVariables(
                arguments = environment.arguments,
                currentEngineData = engineResults.currentObjectResult,
                queryEngineData = engineResults.queryResult,
                engineExecutionContext = localExecutionContext,
                environment.graphQlContext,
                environment.locale,
                queryPlan = queryPlan,
            )
            selectionSetFactory.engineSelectionSet(rss.selections, variables.toMap())
        }
        val queryErrorMessage = "add it to @Resolver's queryValueFragment before accessing it via Context.queryValue"

        val queryValueFactory = EngineObjectDataFactory { instrumentationContext ->
            SyncEngineObjectDataFactory.resolve(
                engineResults.queryResult,
                queryErrorMessage,
                queryEngineSelectionSet,
                parentPath = ResultPath.rootPath(),
                instrumentationContext = instrumentationContext,
            )
        }

        return Factories(objectValueFactory, queryValueFactory)
    }

    private suspend fun resolveField(
        environment: DataFetchingEnvironment,
        factories: Factories,
        engineExecutionContext: EngineExecutionContext
    ) = fieldResolverDispatcher.resolve(
        environment.arguments,
        factories.objectValueFactory,
        factories.queryValueFactory,
        engineSelectionSet(engineExecutionContext),
        engineExecutionContext
    )

    private fun getEngineResults(environment: DataFetchingEnvironment): EngineResults {
        val engineLoaderContext = environment.findLocalContextForType<EngineResultLocalContext>()
        val queryEngineResult = engineLoaderContext.queryEngineResult
        val currentObjectEngineResult = engineLoaderContext.currentObjectEngineResult
        assert(currentObjectEngineResult.type.name == typeName)
        return EngineResults(currentObjectEngineResult, queryEngineResult)
    }
}
