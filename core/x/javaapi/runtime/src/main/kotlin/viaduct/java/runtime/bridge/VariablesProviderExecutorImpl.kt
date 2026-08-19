package viaduct.java.runtime.bridge

import graphql.schema.GraphQLInputObjectType
import javax.inject.Provider
import kotlinx.coroutines.future.await
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.VariablesResolver
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.java.api.context.VariablesProviderContext
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.types.Arguments
import viaduct.java.api.variables.VariablesProvider

/**
 * Bridges a Java [VariablesProvider] to the engine's [VariablesResolver] SPI.
 *
 * Each invocation creates a fresh provider instance via [provider], builds a typed
 * [VariablesProviderContext] from the engine's per-invocation data, and post-processes the
 * returned values so that Tenant API wrapper objects are normalized to the raw forms the engine
 * expects before GraphQL Java performs schema coercion.
 *
 * Mirrors the Kotlin equivalent [viaduct.tenant.runtime.execution.VariablesProviderExecutor].
 */
class VariablesProviderExecutorImpl(
    override val variableNames: Set<String>,
    private val provider: Provider<out VariablesProvider<*>>,
    private val argumentsClass: Class<out Arguments>? = null,
    private val grtPackagePrefix: String? = null,
) : VariablesResolver {
    override suspend fun resolve(
        ctx: VariablesResolver.ResolveCtx,
        context: EngineExecutionContext
    ): Map<String, Any?> {
        // Per-request InternalContext attached to the typed Arguments and its nested input GRTs.
        val internalContext = buildInternalContext(context, grtPackagePrefix)
        val arguments = handleFrameworkErrors("VariablesProvider: createArguments") {
            createArguments(ctx.arguments, internalContext)
        }
        val variablesContext = SimpleVariablesProviderContext(
            requestContext = context.requestContext,
            arguments = arguments,
            engineExecutionContext = context,
            grtPackagePrefix = grtPackagePrefix,
        )

        val raw = handleTenantErrorsSuspend("VariablesProvider") {
            invoke(variablesContext)
        }

        return JavaTenantApiInputValueNormalizer.normalizeVariablesForEngine(raw, context)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun invoke(ctx: VariablesProviderContext<Arguments>): Map<String, Any?> {
        val instance = provider.get() as VariablesProvider<Arguments>
        val future = instance.provide(ctx)
        @Suppress("UNCHECKED_CAST")
        return (future.await() as Map<String, Any?>?) ?: emptyMap()
    }

    private fun createArguments(
        argumentMap: Map<String, Any?>,
        internalContext: InternalContext?
    ): Arguments? {
        if (
            argumentsClass == null ||
            Arguments.isNoArgumentsClass(argumentsClass)
        ) {
            return null
        }

        val graphQLInputObjectType: GraphQLInputObjectType? = internalContext?.let { ctx ->
            buildArgumentsInputType(argumentsClass, ctx)
        }

        val constructor = argumentsClass.getDeclaredConstructor(
            InternalContext::class.java,
            Map::class.java,
            GraphQLInputObjectType::class.java
        )
        return constructor.newInstance(internalContext, argumentMap, graphQLInputObjectType) as Arguments
    }
}
