package viaduct.java.runtime.bridge

import javax.inject.Provider
import kotlinx.coroutines.future.await
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.VariablesResolver
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.java.api.context.VariablesProviderContext
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.InputBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.variables.VariablesProvider

/**
 * Bridges a Java [VariablesProvider] to the engine's [VariablesResolver] SPI.
 *
 * Each invocation creates a fresh provider instance via [provider], builds a typed
 * [VariablesProviderContext] from the engine's per-invocation data, and post-processes the
 * returned values so that [GlobalID] and [InputBase] are normalised to the raw forms the
 * engine expects (serialized id strings and underlying input maps respectively).
 *
 * Mirrors the Kotlin equivalent [viaduct.tenant.runtime.execution.VariablesProviderExecutor].
 */
class VariablesProviderExecutorImpl(
    override val variableNames: Set<String>,
    private val provider: Provider<out VariablesProvider<*>>,
    private val argumentsClass: Class<out Arguments>? = null,
) : VariablesResolver {
    override suspend fun resolve(
        ctx: VariablesResolver.ResolveCtx,
        context: EngineExecutionContext
    ): Map<String, Any?> {
        val arguments = handleFrameworkErrors("VariablesProvider: createArguments") {
            createArguments(ctx.arguments)
        }
        val variablesContext = SimpleVariablesProviderContext(
            requestContext = context.requestContext,
            arguments = arguments,
            engineExecutionContext = context,
        )

        val raw = handleTenantErrorsSuspend("VariablesProvider") {
            invoke(variablesContext)
        }

        return raw.mapValues { (_, value) -> normalize(value, context) }
    }

    /**
     * Convert tenant-returned values to the raw forms the engine expects, recursing through
     * nested input maps and lists. [GlobalID] becomes a serialized id string and [InputBase]
     * becomes its underlying map (with nested inputs likewise unwrapped).
     */
    private fun normalize(
        value: Any?,
        context: EngineExecutionContext
    ): Any? =
        when (value) {
            null -> null
            is GlobalID<*> -> context.globalIDCodec.serialize(value.type.name, value.internalID)
            is InputBase -> value.getInputData().mapValues { (_, v) -> normalize(v, context) }
            is Map<*, *> -> value.mapValues { (_, v) -> normalize(v, context) }
            is List<*> -> value.map { normalize(it, context) }
            else -> value
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun invoke(ctx: VariablesProviderContext<Arguments>): Map<String, Any?> {
        val instance = provider.get() as VariablesProvider<Arguments>
        val future = instance.provide(ctx)
        @Suppress("UNCHECKED_CAST")
        return (future.await() as Map<String, Any?>?) ?: emptyMap()
    }

    private fun createArguments(argumentMap: Map<String, Any?>): Arguments? {
        if (argumentsClass == null || argumentsClass == Arguments.None::class.java) {
            return null
        }
        val constructor = argumentsClass.getDeclaredConstructor(Map::class.java)
        return constructor.newInstance(argumentMap) as Arguments
    }
}
