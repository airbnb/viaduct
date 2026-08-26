package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Exposed
import com.google.inject.PrivateModule
import com.google.inject.Provides
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton
import viaduct.engine.EngineConfiguration
import viaduct.engine.EngineFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.CheckerExecutorFactoryCreator
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.execution.QueryPlanFactory
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.engine.runtime.tenantloading.ExecutorValidator
import viaduct.engine.runtime.tenantloading.MissingResolverValidationCtx
import viaduct.engine.runtime.tenantloading.MissingResolverValidator
import viaduct.engine.runtime.tenantloading.StandardDispatcherRegistryFactory
import viaduct.engine.runtime.validation.Validator
import viaduct.service.runtime.builtinresolvers.builtinModuleConfigSources
import viaduct.utils.slf4j.logger

internal class SchemaScopedModule(
    private val schemaConfig: SchemaConfiguration,
    private val existingRegistry: EngineRegistry? = null,
) : AbstractModule() {
    companion object {
        private val log by logger()
    }

    override fun configure() {
        bind(SchemaConfiguration::class.java).toInstance(schemaConfig)

        bind(RequiredSelectionSetRegistry::class.java).to(DispatcherRegistry::class.java)

        install(SchemaRegistryModule(existingRegistry))
    }

    private class SchemaRegistryModule(
        private val existingRegistry: EngineRegistry?
    ) : PrivateModule() {
        override fun configure() {
        }

        @Qualifier
        @Retention(AnnotationRetention.RUNTIME)
        annotation class BaseRegistry

        @Provides
        @Singleton
        @BaseRegistry
        fun providesBaseEngineRegistry(
            factory: EngineRegistry.Factory,
            config: SchemaConfiguration,
        ): EngineRegistry {
            return when {
                existingRegistry != null -> factory.createWithReusedSchemas(existingRegistry)
                else -> factory.create(config)
            }
        }

        @Provides
        @Singleton
        @Exposed
        fun providesFullViaductSchema(
            @BaseRegistry engineRegistry: EngineRegistry
        ): ViaductSchema {
            return engineRegistry.getFullSchema()
        }

        @Provides
        @Singleton
        @Exposed
        fun providesEngineRegistry(
            @BaseRegistry registry: EngineRegistry,
            engineFactory: EngineFactory,
        ): EngineRegistry {
            registry.setEngineFactory(engineFactory)
            return registry
        }
    }

    @Provides
    @Singleton
    fun providesExecutorValidator(schema: ViaductSchema): ExecutorValidator {
        return ExecutorValidator(schema)
    }

    @Provides
    @Singleton
    fun providesCheckerExecutorFactory(
        schema: ViaductSchema,
        creator: CheckerExecutorFactoryCreator,
    ): CheckerExecutorFactory {
        return creator.create(schema)
    }

    @Provides
    @Singleton
    fun providesDispatcherRegistry(
        validator: ExecutorValidator,
        checkerExecutorFactory: CheckerExecutorFactory,
        schema: ViaductSchema,
        moduleBootstrapConfiguration: ModuleBootstrapConfiguration,
        proxyResolverFactory: ProxyResolverFactory,
        resolverInstrumentation: ViaductResolverInstrumentation,
        @Named("lenientResolverValidation") lenientResolverValidation: Boolean,
    ): DispatcherRegistry {
        log.info("Creating DispatcherRegistry for Viaduct Modern")
        val startTime = System.currentTimeMillis()

        val missingResolverValidator: Validator<MissingResolverValidationCtx> =
            if (lenientResolverValidation) {
                Validator.Unvalidated
            } else {
                MissingResolverValidator(schema)
            }

        // Generated built-in configs are schema-derived, so they are assembled from the schema
        // rather than at builder time. They flow through the same file-based bootstrap path as
        // resource-backed tenant modules but are passed separately so they bootstrap last and win
        // coordinate dedup over any tenant-supplied resolver at the same coordinate. Generation is
        // deferred (a provider) so that schema-validation failures surface inside the factory's
        // create() and are unwrapped into a friendly build error rather than a raw Guice failure.
        val dispatcherRegistry = StandardDispatcherRegistryFactory(
            moduleConfigSources = moduleBootstrapConfiguration.moduleConfigSources,
            tenantModuleInjectorFactory = moduleBootstrapConfiguration.tenantModuleInjectorFactory,
            validator = validator,
            checkerExecutorFactory = checkerExecutorFactory,
            builtinModuleConfigSourcesProvider = {
                builtinModuleConfigSources(
                    schema = schema,
                    defaultQueryNodeResolversEnabled = moduleBootstrapConfiguration.defaultQueryNodeResolversEnabled,
                )
            },
            grtPackagePrefix = moduleBootstrapConfiguration.grtPackagePrefix,
            resolverInstrumentation = resolverInstrumentation,
            proxyResolverFactory = proxyResolverFactory,
            missingResolverValidator = missingResolverValidator,
        ).create(schema)
        val elapsedTime = System.currentTimeMillis() - startTime
        log.info("Created DispatcherRegistry for Viaduct Modern after [{}] ms", elapsedTime)
        return dispatcherRegistry
    }

    @Provides
    @Singleton
    fun providesQueryPlanFactory(config: EngineConfiguration): QueryPlanFactory {
        return QueryPlanFactory.Cached(config.meterRegistry)
    }

    @Provides
    @Singleton
    fun providesEngineFactory(
        config: EngineConfiguration,
        dispatcherRegistry: DispatcherRegistry,
        tenantNameResolver: TenantNameResolver,
        queryPlanFactory: QueryPlanFactory,
    ): EngineFactory {
        return EngineFactory(
            config = config.copy(tenantNameResolver = tenantNameResolver),
            dispatcherRegistry = dispatcherRegistry,
            queryPlanFactory = queryPlanFactory,
        )
    }
}
