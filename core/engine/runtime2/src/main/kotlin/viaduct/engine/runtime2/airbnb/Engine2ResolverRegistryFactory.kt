@file:Suppress("DEPRECATION")

package viaduct.engine.runtime2.airbnb

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime2.bootstrap.dispatcherRegistryInputs
import viaduct.engine.runtime2.bootstrap.resolverRegistryOf
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.NodeResolverFunction
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDeclaration
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

/** Airbnb-only hook: true enables declared inputs, false disables them; choices must stay stable within each request. */
@Deprecated("Airbnb use only")
fun resolverRegistryWithRequestDependencies(
    schema: ViaductAndGJSchema,
    nodeResolvers: Map<ViaductSchema.Object, NodeResolverFunction>,
    fieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>,
    fieldCheckers: Map<ViaductSchema.ObjectField, FieldCheckerResolver> = emptyMap(),
    typeCheckers: Map<ViaductSchema.Object, TypeCheckerResolver> = emptyMap(),
    variableProviders: Map<Arguments.Variable, VariableDeclaration>,
    fieldDependenciesEnabled: Map<ViaductSchema.ObjectField, (EngineExecutionContext?) -> Boolean>,
): ResolverRegistry =
    resolverRegistryOf(
        schema,
        nodeResolvers,
        fieldResolvers,
        fieldCheckers,
        typeCheckers,
        variableProviders,
        fieldDependenciesEnabled,
    )

/**
 * Airbnb-only hook for supplying Engine2 resolver registries through `StandardViaduct.Builder.withEngine2ResolverRegistryFactory`.
 * The factory runs once per schema-scoped engine and may adapt the supplied dispatchers or provide alternative resolvers.
 */
@Deprecated("Airbnb use only")
fun interface Engine2ResolverRegistryFactory {
    /** Creates one shared registry after the full schema, its lowered representation, and instrumented dispatchers are available. */
    fun create(
        fullSchema: EngineSchema,
        schemas: ViaductAndGJSchema,
        dispatcherRegistry: DispatcherRegistry,
        fieldSelectivityProvider: FieldSelectivityProvider,
    ): ResolverRegistry
}

/** Default implementation of the Airbnb registry hook, adapting registered tenant dispatchers for Engine2. */
@Deprecated("Airbnb use only")
object DispatcherResolverRegistryFactory : Engine2ResolverRegistryFactory {
    override fun create(
        fullSchema: EngineSchema,
        schemas: ViaductAndGJSchema,
        dispatcherRegistry: DispatcherRegistry,
        fieldSelectivityProvider: FieldSelectivityProvider,
    ): ResolverRegistry {
        val inputs = dispatcherRegistryInputs(fullSchema, schemas, dispatcherRegistry, fieldSelectivityProvider)
        return resolverRegistryOf(
            schema = schemas,
            fieldResolvers = inputs.fieldResolvers,
            nodeResolvers = inputs.nodeResolvers,
            fieldCheckers = inputs.fieldCheckers,
            typeCheckers = inputs.typeCheckers,
            variableProviders = inputs.variableProviders,
        )
    }
}
