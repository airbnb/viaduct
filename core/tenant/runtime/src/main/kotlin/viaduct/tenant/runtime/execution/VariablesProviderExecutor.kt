package viaduct.tenant.runtime.execution

import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.internal
import viaduct.api.types.Arguments
import viaduct.apiannotations.Attribution
import viaduct.apiannotations.AttributionContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.VariablesResolver
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.tenant.runtime.context.factory.VariablesProviderContextFactory
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/** Adapts a tenant-defined [VariablesProvider] to a [VariablesResolver] that can be executed by the engine */
class VariablesProviderExecutor(
    val variablesProvider: VariablesProviderInfo,
    val variablesProviderContextFactory: VariablesProviderContextFactory,
) : VariablesResolver {
    override val variableNames: Set<String> = variablesProvider.variables

    override suspend fun resolve(
        ctx: VariablesResolver.ResolveCtx,
        context: EngineExecutionContext
    ): Map<String, Any?> {
        val provider = variablesProvider.provider.get()
        val variablesProviderCtx = variablesProviderContextFactory.createVariablesProviderContext(
            engineExecutionContext = context,
            requestContext = context.requestContext,
            rawArguments = ctx.arguments
        )

        val providedVars = handleTenantErrorsSuspend("VariablesProvider") {
            provideVariables(provider, variablesProviderCtx)
        }
        return providedVars.mapValues {
            // The Viaduct engine expects the values to be scalar types or maps, so converting InputLikeBase and GlobalID to their internal representations
            when (val value = it.value) {
                is GlobalID<*> -> variablesProviderCtx.internal.globalIDCodec.serialize(value.type.name, value.internalID)
                is InputLikeBase -> value.inputData
                else -> value
            }
        }
    }

    @Attribution(AttributionContext.TENANT)
    private suspend fun provideVariables(
        provider: VariablesProvider<*>,
        context: VariablesProviderContext<Arguments>
    ): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (provider as VariablesProvider<Arguments>).provide(context)
    }
}
