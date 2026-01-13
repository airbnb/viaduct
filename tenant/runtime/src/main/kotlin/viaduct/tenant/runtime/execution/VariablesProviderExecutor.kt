package viaduct.tenant.runtime.execution

import viaduct.api.VariablesProvider
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.internal
import viaduct.api.types.Arguments
import viaduct.engine.api.VariablesResolver
import viaduct.tenant.runtime.context.factory.VariablesProviderContextFactory
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/** Adapts a tenant-defined [VariablesProvider] to a [VariablesResolver] that can be executed by the engine */
class VariablesProviderExecutor(
    val variablesProvider: VariablesProviderInfo,
    val variablesProviderContextFactory: VariablesProviderContextFactory,
) : VariablesResolver {
    override val variableNames: Set<String> = variablesProvider.variables

    override suspend fun resolve(ctx: VariablesResolver.ResolveCtx): Map<String, Any?> {
        val provider = variablesProvider.provider.get()
        val variablesProviderCtx = variablesProviderContextFactory.createVariablesProviderContext(
            engineExecutionContext = ctx.engineExecutionContext,
            requestContext = ctx.engineExecutionContext.requestContext,
            rawArguments = ctx.arguments
        )

        @Suppress("UNCHECKED_CAST")
        return (provider as VariablesProvider<Arguments>).provide(variablesProviderCtx).mapValues {
            // The Viaduct engine expects the values to be scalar types or maps, so converting InputLikeBase and GlobalID to their internal representations
            when (val value = it.value) {
                is GlobalID<*> -> variablesProviderCtx.internal.globalIDCodec.serialize(value.type.name, value.internalID)
                is InputLikeBase -> value.inputData
                else -> value
            }
        }
    }
}
