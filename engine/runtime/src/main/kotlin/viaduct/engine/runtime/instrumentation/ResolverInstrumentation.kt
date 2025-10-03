package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.FieldResolverDispatcher
import viaduct.engine.api.FieldResolverDispatcherRegistry
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.ResolverDataFetcher
import viaduct.graphql.utils.asNamedElement

/**
 * Instrumentation that executes @Resolver classes for Viaduct Modern
 */
class ResolverInstrumentation(
    private val dispatcherRegistry: FieldResolverDispatcherRegistry, // Modern resolvers
    private val checkerRegistry: FieldCheckerDispatcherRegistry,
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop
) : Instrumentation {
    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): DataFetcher<*> {
        val dfEnv = parameters.environment
        val typeName = dfEnv.parentType.asNamedElement().name
        val fieldName = dfEnv.fieldDefinition.name

        val resolverDispatcher = resolverDispatcher(typeName, fieldName) ?: return dataFetcher
        val checkerDispatcher = checkerRegistry.getFieldCheckerDispatcher(typeName, fieldName)
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
