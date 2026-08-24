@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.tenantloading

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory.getLogger
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.engine.api.spi.TenantModuleException
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.CheckerDispatcherImpl
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.NodeResolverDispatcherImpl
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedCheckerDispatcher
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedNodeResolverDispatcher
import viaduct.engine.runtime.validation.Validator
import viaduct.service.api.spi.NaiveTenantModuleInjectorFactory
import viaduct.service.api.spi.TenantModuleInjectorFactory

/** Builds a validated [DispatcherRegistry] from tenant module contributions. */
interface DispatcherRegistryFactory {
    /** Creates and returns a validated [DispatcherRegistry]. */
    fun create(schema: ViaductSchema): DispatcherRegistry
}

/**
 * Shared implementation of the [DispatcherRegistry] assembly algorithm.
 *
 * Subclasses supply the [TenantModuleBootstrapper]s (via [tenantModuleBootstrappers]); this base
 * concatenates their executors into dispatchers, registers access checkers, and runs validation.
 */
abstract class AbstractDispatcherRegistryFactory(
    private val validator: Validator<ExecutorValidatorContext>,
    private val checkerExecutorFactory: CheckerExecutorFactory,
    private val resolverInstrumentation: ViaductResolverInstrumentation = ViaductResolverInstrumentation.DEFAULT,
    private val proxyResolverFactory: ProxyResolverFactory = ProxyResolverFactory.NO_OP,
    private val missingResolverValidator: Validator<MissingResolverValidationCtx> = Validator.Unvalidated,
) : DispatcherRegistryFactory {
    companion object {
        private fun log() = getLogger(this::class.java.name.substringBefore("\$Companion"))
    }

    /**
     * Produce the tenant module bootstrappers whose executors are assembled into the registry.
     * Runs on [Dispatchers.Default] inside a `runBlocking` scope during [create].
     */
    protected abstract suspend fun tenantModuleBootstrappers(): List<TenantModuleBootstrapper>

    final override fun create(schema: ViaductSchema): DispatcherRegistry {
        val fieldResolverDispatchers = mutableMapOf<Coordinate, FieldResolverDispatcher>()
        val nodeResolverDispatchers = mutableMapOf<String, NodeResolverDispatcher>()
        val fieldCheckerDispatchers = mutableMapOf<Coordinate, CheckerDispatcher>()
        val typeCheckerDispatchers = mutableMapOf<String, CheckerDispatcher>()

        // Create a collection of executors for validation purpose
        val fieldResolverExecutorsToValidate = mutableMapOf<Coordinate, FieldResolverExecutor>()
        val nodeResolverExecutorsToValidate = mutableMapOf<String, NodeResolverExecutor>()
        val fieldCheckerExecutorsToValidate = mutableMapOf<Coordinate, CheckerExecutor>()
        val typeCheckerExecutorsToValidate = mutableMapOf<String, CheckerExecutor>()

        val tenantModuleBootstrappers = runBlocking(Dispatchers.Default) {
            tenantModuleBootstrappers()
        }

        // Concatenate resolvers from all bootstrappers into a single list.
        for (tenant in tenantModuleBootstrappers) {
            val (tenantFieldResolverExecutors, tenantNodeResolverExecutors) = try {
                val tenantFieldResolverExecutors = tenant.fieldResolverExecutors(schema)
                val tenantNodeResolverExecutors = tenant.nodeResolverExecutors(schema)
                Pair(tenantFieldResolverExecutors, tenantNodeResolverExecutors)
            } catch (e: TenantModuleException) {
                log().warn("Could not bootstrap $tenant", e)
                continue // still concatenate everything else, just skipping one tenant
            }

            var tenantContributesExecutors = false
            for ((fieldCoord, executor) in tenantFieldResolverExecutors) {
                val finalExecutor = proxyResolverFactory.proxyField(executor) ?: executor
                // Resolver coordinates are globally keyed. Duplicate registrations are deduped
                // silently here, with the later registration winning.
                fieldResolverDispatchers[fieldCoord] = FieldResolverDispatcherImpl(finalExecutor)
                // The proxy executor is validated because the engine uses the proxy's RSS and type
                // contract at runtime. Validating the original would check RSS that is no longer
                // in effect when a proxy overrides it.
                fieldResolverExecutorsToValidate[fieldCoord] = finalExecutor
                tenantContributesExecutors = true
            }
            for ((typeName, executor) in tenantNodeResolverExecutors) {
                val finalExecutor = proxyResolverFactory.proxyNode(executor) ?: executor
                nodeResolverDispatchers[typeName] = InstrumentedNodeResolverDispatcher(NodeResolverDispatcherImpl(finalExecutor), resolverInstrumentation)
                // Same reasoning as field executors above: the proxy is validated.
                nodeResolverExecutorsToValidate[typeName] = finalExecutor
                tenantContributesExecutors = true
            }
            if (!tenantContributesExecutors) {
                log().warn("Bootstrapping $tenant (a ${tenant.javaClass.name}) did not contribute any executors")
            }
        }

        // Register access checkers
        schema.schema.allTypesAsList.forEach typeLoop@{ type ->
            // Only register checkers for object types (skip types starting with "__" which are reserved by GraphQL)
            if (type is GraphQLObjectType && !type.name.startsWith("__")) {
                val typeName = type.name
                type.fields.forEach fieldLoop@{ field ->
                    // skip fields starting with "__" which are reserved by GraphQL
                    if (field.name.startsWith("__")) {
                        return@fieldLoop
                    }
                    checkerExecutorFactory.checkerExecutorForField(schema, typeName, field.name)?.let {
                        val fieldCoord = typeName to field.name
                        fieldCheckerDispatchers[fieldCoord] = InstrumentedCheckerDispatcher(CheckerDispatcherImpl(it), resolverInstrumentation)
                        fieldCheckerExecutorsToValidate[fieldCoord] = it
                    }
                }
                checkerExecutorFactory.checkerExecutorForType(schema, typeName)?.let {
                    typeCheckerDispatchers[typeName] = InstrumentedCheckerDispatcher(CheckerDispatcherImpl(it), resolverInstrumentation)
                    typeCheckerExecutorsToValidate[typeName] = it
                }
            }
        }
        val dispatcherRegistry = DispatcherRegistry.Impl(fieldResolverDispatchers.toMap(), nodeResolverDispatchers.toMap(), fieldCheckerDispatchers.toMap(), typeCheckerDispatchers.toMap())

        validator.validate(
            ExecutorValidatorContext(
                fieldResolverExecutorsToValidate,
                nodeResolverExecutorsToValidate,
                fieldCheckerExecutorsToValidate,
                typeCheckerExecutorsToValidate,
                dispatcherRegistry, // need requiredSelectionSetRegistry on dispatcherRegistry for validation
            )
        )

        missingResolverValidator.validate(MissingResolverValidationCtx(dispatcherRegistry))

        return dispatcherRegistry
    }
}

/**
 * Assembles a [DispatcherRegistry] from file-based tenant module configs.
 *
 * [moduleConfigSources] (resource-backed tenant modules) are bootstrapped in-place via
 * [ModuleConfigBootstrapper] using [tenantModuleInjectorFactory]. Tenant APIs that do not express
 * themselves as config sources (classic wiring, remote resolvers) come in through the optional
 * [compatBootstrapper]; its [TenantModuleBootstrapper]s are concatenated after those built from the
 * config sources.
 *
 * Generated built-in resolvers (`Query.node`/`Query.nodes` and `@namespaceType`) are supplied via
 * [builtinModuleConfigSourcesProvider] and are bootstrapped **last**. Resolver coordinates are
 * deduped with the later registration winning, so bootstrapping the built-ins last gives them
 * precedence over any tenant-supplied resolver registered at the same coordinate. Because built-in
 * executor factories are schema-independent and ignore both the code injector and the GRT prefix,
 * they are bootstrapped in their own pass with a [NaiveTenantModuleInjectorFactory]; this also holds
 * the service-supplied [tenantModuleInjectorFactory]'s `onBootstrapComplete` contract to a single
 * invocation.
 *
 * [builtinModuleConfigSourcesProvider] is a provider rather than a precomputed list so that
 * schema-derived generation (which can throw for an invalid schema, e.g. a wrapped `@namespaceType`
 * field) runs inside [create], keeping such failures within the startup error boundary that
 * `StandardViaduct` unwraps into a friendly build error.
 */
class StandardDispatcherRegistryFactory(
    private val moduleConfigSources: List<ModuleConfigSource>,
    private val tenantModuleInjectorFactory: TenantModuleInjectorFactory,
    validator: Validator<ExecutorValidatorContext>,
    checkerExecutorFactory: CheckerExecutorFactory,
    private val compatBootstrapper: TenantAPIBootstrapper? = null,
    private val builtinModuleConfigSourcesProvider: () -> List<ModuleConfigSource> = { emptyList() },
    private val grtPackagePrefix: String? = null,
    resolverInstrumentation: ViaductResolverInstrumentation = ViaductResolverInstrumentation.DEFAULT,
    proxyResolverFactory: ProxyResolverFactory = ProxyResolverFactory.NO_OP,
    missingResolverValidator: Validator<MissingResolverValidationCtx> = Validator.Unvalidated,
) : AbstractDispatcherRegistryFactory(
        validator = validator,
        checkerExecutorFactory = checkerExecutorFactory,
        resolverInstrumentation = resolverInstrumentation,
        proxyResolverFactory = proxyResolverFactory,
        missingResolverValidator = missingResolverValidator,
    ) {
    override suspend fun tenantModuleBootstrappers(): List<TenantModuleBootstrapper> {
        val fromConfigSources: List<TenantModuleBootstrapper> =
            ModuleConfigBootstrapper(
                tenantModuleInjectorFactory = tenantModuleInjectorFactory,
                grtPackagePrefix = grtPackagePrefix,
            ).bootstrap(moduleConfigSources)
        val fromCompat = compatBootstrapper?.tenantModuleBootstrappers() ?: emptyList()
        val fromBuiltins: List<TenantModuleBootstrapper> =
            ModuleConfigBootstrapper(
                tenantModuleInjectorFactory = NaiveTenantModuleInjectorFactory,
                grtPackagePrefix = grtPackagePrefix,
            ).bootstrap(builtinModuleConfigSourcesProvider())
        return fromConfigSources + fromCompat + fromBuiltins
    }
}
