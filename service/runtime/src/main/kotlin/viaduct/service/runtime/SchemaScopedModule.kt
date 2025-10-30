package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Exposed
import com.google.inject.PrivateModule
import com.google.inject.Provides
import com.google.inject.Singleton
import javax.inject.Qualifier
import viaduct.engine.EngineConfiguration
import viaduct.engine.EngineFactory
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.CheckerExecutorFactoryCreator
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.FieldResolverDispatcherRegistry
import viaduct.engine.api.NodeResolverDispatcherRegistry
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.api.TypeCheckerDispatcherRegistry
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.tenantloading.DispatcherRegistryFactory
import viaduct.engine.runtime.tenantloading.ExecutorValidator
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.utils.slf4j.logger

internal class SchemaScopedModule(
    private val schemaConfig: SchemaConfiguration
) : AbstractModule() {
    companion object {
        private val log by logger()
    }

    override fun configure() {
        bind(SchemaConfiguration::class.java).toInstance(schemaConfig)

        bind(FieldResolverDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(NodeResolverDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(FieldCheckerDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(TypeCheckerDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(RequiredSelectionSetRegistry::class.java).to(DispatcherRegistry::class.java)

        install(SchemaRegistryModule())
    }

    private class SchemaRegistryModule : PrivateModule() {
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
            return factory.create(config)
        }

        @Provides
        @Singleton
        @Exposed
        fun providesFullViaductSchema(
            @BaseRegistry engineRegistry: EngineRegistry
        ): ViaductSchema {
            return engineRegistry.getSchema(SchemaId.Full)
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
        tenantBootstrapper: TenantAPIBootstrapper,
        @Suppress("UNUSED_PARAMETER") flagManager: FlagManager,
        resolverInstrumentation: ViaductResolverInstrumentation
    ): DispatcherRegistry {
        log.info("Creating DispatcherRegistry for Viaduct Modern")
        val startTime = System.currentTimeMillis()
        val dispatcherRegistry = DispatcherRegistryFactory(tenantBootstrapper, validator, checkerExecutorFactory, resolverInstrumentation).create(schema)
        val elapsedTime = System.currentTimeMillis() - startTime
        log.info("Created DispatcherRegistry for Viaduct Modern after [$elapsedTime] ms")
        return dispatcherRegistry
    }

    @Provides
    @Singleton
    fun providesEngineFactory(
        config: EngineConfiguration,
        dispatcherRegistry: DispatcherRegistry,
    ): EngineFactory {
        return EngineFactory(
            config = config,
            dispatcherRegistry = dispatcherRegistry,
        )
    }
}
