@file:Suppress("DEPRECATION")

package viaduct.engine.runtime2.airbnb

import graphql.execution.ExecutionContext
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime2.bootstrap.dispatcherRegistryInputs
import viaduct.engine.runtime2.bootstrap.resolverRegistryOf
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.schema.ViaductAndGJSchema

/**
 * Airbnb-only hook for supplying Engine2 resolver registries through `StandardViaduct.Builder.withEngine2ResolverRegistryFactory`.
 * The factory runs once per schema-scoped engine and may adapt the supplied dispatchers or provide alternative resolvers.
 */
@Deprecated("Airbnb use only")
fun interface Engine2ResolverRegistryFactory {
    /** Creates a provider after the full schema, its qplan representation, and instrumented dispatchers are available. */
    fun create(
        fullSchema: EngineSchema,
        schemas: ViaductAndGJSchema,
        dispatcherRegistry: DispatcherRegistry,
        fieldSelectivityProvider: FieldSelectivityProvider,
    ): ResolverRegistryProvider
}

/** Airbnb-only provider of schema-scoped planning registries, optionally selected per invocation. */
@Deprecated("Airbnb use only")
fun interface ResolverRegistryProvider {
    /** Returns the registry used to initialize schema-scoped planning and by default for each invocation. */
    fun forBootstrap(): ResolverRegistry

    /** Selects resolvers and their requirements before invocation planning; do not retain the invocation in schema-scoped provider state. */
    fun forExecution(invocation: ExecutionContext): ResolverRegistry = forBootstrap()
}

/** Default implementation of the Airbnb registry hook, adapting registered tenant dispatchers for Engine2. */
@Deprecated("Airbnb use only")
object DispatcherResolverRegistryFactory : Engine2ResolverRegistryFactory {
    override fun create(
        fullSchema: EngineSchema,
        schemas: ViaductAndGJSchema,
        dispatcherRegistry: DispatcherRegistry,
        fieldSelectivityProvider: FieldSelectivityProvider,
    ): ResolverRegistryProvider {
        val inputs = dispatcherRegistryInputs(fullSchema, schemas, dispatcherRegistry, fieldSelectivityProvider)
        val registry = resolverRegistryOf(
            schema = schemas,
            fieldResolvers = inputs.fieldResolvers,
            nodeResolvers = inputs.nodeResolvers,
            fieldCheckers = inputs.fieldCheckers,
            typeCheckers = inputs.typeCheckers,
            variableProviders = inputs.variableProviders,
        )
        return ResolverRegistryProvider { registry }
    }
}
