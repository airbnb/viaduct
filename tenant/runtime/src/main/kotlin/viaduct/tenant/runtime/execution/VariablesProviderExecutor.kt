package viaduct.tenant.runtime.execution

import viaduct.api.VariablesProvider
import viaduct.api.globalid.GlobalID
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.ReflectionLoader
import viaduct.api.types.Arguments
import viaduct.engine.api.VariablesResolver
import viaduct.tenant.runtime.context.ExecutionContextImpl
import viaduct.tenant.runtime.context.VariablesProviderContextImpl
import viaduct.tenant.runtime.context.factory.ArgumentsArgs
import viaduct.tenant.runtime.context.factory.Factory
import viaduct.tenant.runtime.internal.InternalContextImpl
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/** Adapts a tenant-defined [VariablesProvider] to a [VariablesResolver] that can be executed by the engine */
class VariablesProviderExecutor(
    val globalIDCodec: GlobalIDCodec,
    val reflectionLoader: ReflectionLoader,
    val variablesProvider: VariablesProviderInfo,
    val argumentsFactory: Factory<ArgumentsArgs, Arguments>,
) : VariablesResolver {
    override val variableNames: Set<String> = variablesProvider.variables

    override suspend fun resolve(ctx: VariablesResolver.ResolveCtx): Map<String, Any?> {
        val provider = variablesProvider.provider.get()
        val internalContext = InternalContextImpl(ctx.engineExecutionContext.fullSchema, globalIDCodec, reflectionLoader)
        val args = ArgumentsArgs(internalContext, ctx.arguments)
        val variablesProviderCtx = VariablesProviderContextImpl(
            arguments = argumentsFactory(args),
            executionContext = ExecutionContextImpl(internalContext, ctx.engineExecutionContext.requestContext)
        )

        @Suppress("UNCHECKED_CAST")
        return (provider as VariablesProvider<Arguments>).provide(variablesProviderCtx).mapValues {
            // The Viaduct engine expects the values to be scalar types or maps, so converting InputLikeBase and GlobalID to their internal representations
            when (val value = it.value) {
                is GlobalID<*> -> globalIDCodec.serialize(value)
                is InputLikeBase -> value.inputData
                else -> value
            }
        }
    }
}
