package viaduct.tenant.runtime.execution

import viaduct.api.context.VariablesProviderContext
import viaduct.api.internal.internal
import viaduct.api.resolver.VariablesProvider
import viaduct.api.types.Arguments
import viaduct.apiannotations.Attribution
import viaduct.apiannotations.AttributionContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.spi.VariableFromFunctionDefinitions
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.tenant.runtime.TenantApiInputValueNormalizer.normalizeVariablesForEngine
import viaduct.tenant.runtime.context.factory.VariablesProviderContextFactory
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/** Adapts a tenant-defined [VariablesProvider] to the engine's variable interfaces. */
class VariablesProviderExecutor(
    val variablesProvider: VariablesProviderInfo,
    val variablesProviderContextFactory: VariablesProviderContextFactory,
) : VariablesResolver, VariableFromFunctionDefinitions {
    override val variableNames: Set<String> = variablesProvider.variables

    override suspend fun provideVariables(
        objectData: EngineObjectData.Sync,
        arguments: Map<String, Any?>,
        context: EngineExecutionContext,
    ): Map<String, Any?> {
        val provider = variablesProvider.provider.get()
        val variablesProviderCtx = variablesProviderContextFactory.createVariablesProviderContext(
            engineExecutionContext = context,
            requestContext = context.requestContext,
            rawArguments = arguments
        )

        val providedVars = handleTenantErrorsSuspend("VariablesProvider") {
            provideVariables(provider, variablesProviderCtx)
        }
        val normalized = normalizeVariablesForEngine(providedVars, variablesProviderCtx.internal.globalIDCodec)
        check(normalized.keys == variableNames) {
            "VariablesProvider returned invalid variables. " +
                "Extra keys: ${(normalized.keys - variableNames).joinToString(",")}. " +
                "Missing keys: ${(variableNames - normalized.keys).joinToString(",")}."
        }
        return normalized
    }

    override suspend fun resolve(
        ctx: VariablesResolver.ResolveCtx,
        context: EngineExecutionContext
    ): Map<String, Any?> = provideVariables(ctx.objectData, ctx.arguments, context)

    @Attribution(AttributionContext.TENANT)
    private suspend fun provideVariables(
        provider: VariablesProvider<*>,
        context: VariablesProviderContext<Arguments>
    ): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (provider as VariablesProvider<Arguments>).provide(context)
    }
}
