@file:Suppress("DEPRECATION") // CoroutineInterop retained for Airbnb

package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import graphql.schema.GraphQLObjectType
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.dfe.ViaductDataFetchingEnvironment
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.ResolverDataFetcher
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedFieldResolverDispatcher
import viaduct.engine.runtime.suppliesField
import viaduct.graphql.utils.asNamedElement

/**
 * Instrumentation that executes @Resolver classes for Viaduct Modern
 */
class ResolverDataFetcherInstrumentation(
    private val dispatcherRegistry: DispatcherRegistry, // Modern resolvers
    private val resolverInstrumentation: ViaductResolverInstrumentation = ViaductResolverInstrumentation.DEFAULT,
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
    private val tenantNameResolver: TenantNameResolver = TenantNameResolver(),
) : ViaductModernGJInstrumentation {
    override fun createState(parameters: InstrumentationCreateStateParameters): InstrumentationState? = null

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
        if (isSuppliedByParent(dfEnv, fieldName)) {
            return dataFetcher
        }

        val resolverDispatcher = resolverDispatcher(typeName, fieldName) ?: return dataFetcher

        val instrumentedDispatcher = InstrumentedFieldResolverDispatcher(resolverDispatcher, resolverInstrumentation, coordinate = typeName to fieldName)

        return ResolverDataFetcher(
            typeName = typeName,
            fieldName = fieldName,
            fieldResolverDispatcher = instrumentedDispatcher,
            coroutineInterop = coroutineInterop,
            tenantNameResolver = tenantNameResolver,
        )
    }

    /** Whether a [viaduct.engine.api.ParentSuppliedValue] above this field supplies it, so it is read rather than resolved. */
    private fun isSuppliedByParent(
        environment: ViaductDataFetchingEnvironment,
        fieldName: String,
    ): Boolean {
        val parentType = environment.parentType as? GraphQLObjectType ?: return false
        val parent = environment.getLocalContextForType<EngineResultLocalContext>()?.currentObjectEngineResult ?: return false
        return parent.matSource?.suppliesField(parentType, fieldName) == true
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
