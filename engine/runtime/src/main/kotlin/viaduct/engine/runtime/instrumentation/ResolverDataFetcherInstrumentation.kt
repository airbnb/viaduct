package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.api.ViaductDataFetchingEnvironment
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.ResolverDataFetcher
import viaduct.graphql.utils.asNamedElement

/**
 * Instrumentation that executes @Resolver classes for Viaduct Modern
 */
class ResolverDataFetcherInstrumentation(
    private val dispatcherRegistry: DispatcherRegistry, // Modern resolvers
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop
) : ViaductModernGJInstrumentation {
    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): DataFetcher<*> {
        val dfEnv = parameters.environment as ViaductDataFetchingEnvironment

        val resolutionPolicy = dfEnv.engineExecutionContext.fieldScope.resolutionPolicy
        if (resolutionPolicy == ResolutionPolicy.PARENT_MANAGED) {
            return dataFetcher
        }

        val typeName = dfEnv.parentType.asNamedElement().name
        val fieldName = dfEnv.fieldDefinition.name

        val resolverDispatcher = resolverDispatcher(typeName, fieldName) ?: return dataFetcher
        val checkerDispatcher = dispatcherRegistry.getFieldCheckerDispatcher(typeName, fieldName)
        return ResolverDataFetcher(
            typeName = typeName,
            fieldName = fieldName,
            fieldResolverDispatcher = resolverDispatcher,
            checkerDispatcher = checkerDispatcher,
            coroutineInterop = coroutineInterop
        )
    }

    fun hasResolver(
        typeName: String,
        fieldName: String
    ): Boolean {
        return resolverDispatcher(typeName, fieldName) != null
    }

    private fun resolverDispatcher(
        typeName: String,
        fieldName: String
    ): FieldResolverDispatcher? {
        return dispatcherRegistry.getFieldResolverDispatcher(typeName, fieldName)
    }
}
