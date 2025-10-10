package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Exposed
import com.google.inject.PrivateModule
import com.google.inject.Provides
import com.google.inject.Singleton
import javax.inject.Qualifier
import viaduct.engine.Engine
import viaduct.engine.EngineImpl
import viaduct.engine.SchemaFactory
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.CheckerExecutorFactoryCreator
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.FieldResolverDispatcherRegistry
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.NodeResolverDispatcherRegistry
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.api.TypeCheckerDispatcherRegistry
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextFactory
import viaduct.engine.runtime.instrumentation.ResolverDataFetcherInstrumentation
import viaduct.engine.runtime.tenantloading.CheckerSelectionSetsAreProperlyTyped
import viaduct.engine.runtime.tenantloading.DispatcherRegistryFactory
import viaduct.engine.runtime.tenantloading.ExecutorValidator
import viaduct.engine.runtime.tenantloading.FromArgumentVariablesHaveValidPaths
import viaduct.engine.runtime.tenantloading.FromFieldVariablesHaveValidPaths
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreAcyclic
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreSchematicallyValid
import viaduct.engine.runtime.tenantloading.ResolverSelectionSetsAreProperlyTyped
import viaduct.engine.runtime.validation.Validator
import viaduct.engine.runtime.validation.Validator.Companion.flatten
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.utils.slf4j.logger

/**
 * Module for schema-specific providers. These components are created per child injector,
 * making them effectively schema-scoped when @Singleton is used in the child.
 *
 * Key Pattern: @Singleton in child injector = schema-scoped (one per StandardViaduct instance)
 * Configuration comes from the parent injector (CoreUtilitiesModule, ViaductBuilderConfigurationModule).
 */
internal class ViaductInternalEngineModule : AbstractModule() {
    companion object {
        private val log by logger()
    }

    override fun configure() {
        bind(FieldResolverDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(NodeResolverDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(FieldCheckerDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(TypeCheckerDispatcherRegistry::class.java).to(DispatcherRegistry::class.java)
        bind(RequiredSelectionSetRegistry::class.java).to(DispatcherRegistry::class.java)

        // Install private module to manage engine registry creation and exposure
        install(ViaductSchemaModule())

        bind(Engine.Factory::class.java).to(EngineImpl.FactoryImpl::class.java)
    }

    @Provides
    @Singleton
    fun provideDocumentProviderFactory(configuration: ViaductBuilderConfiguration): DocumentProviderFactory {
        return configuration.documentProviderFactory
    }

    @Provides
    @Singleton
    fun provideSchemaFactory(coroutineInterop: CoroutineInterop): SchemaFactory {
        return SchemaFactory(coroutineInterop)
    }

    @Provides
    @Singleton
    fun provideEngineExecutionContextFactory(
        fullSchema: ViaductSchema,
        dispatcherRegistry: DispatcherRegistry,
        fragmentLoader: FragmentLoader,
        resolverInstrumentation: ResolverDataFetcherInstrumentation,
        flagManager: FlagManager,
    ): EngineExecutionContextFactory {
        return EngineExecutionContextFactory(
            fullSchema,
            dispatcherRegistry,
            fragmentLoader,
            resolverInstrumentation,
            flagManager,
        )
    }

    /**
     * Private module that creates the engine registry once and exposes only what's needed publicly.
     * This ensures single object creation while maintaining proper dependency separation.
     *
     * Exposes:
     * - ViaductSchema: For components needing only schema definition (validation, dispatchers)
     * - EngineRegistry: For components needing full execution functionality (StandardViaduct)
     *
     * Encapsulates: The base uninitialized registry (created once, used internally)
     */
    private class ViaductSchemaModule : PrivateModule() {
        override fun configure() {
            // Nothing to bind here - everything is provided via @Provides methods
        }

        @Qualifier
        @Retention(AnnotationRetention.RUNTIME)
        annotation class BaseRegistry

        /**
         * Creates the base EngineRegistry (internal to this module only).
         * We can safely create this without any schema dependencies.
         */
        @Provides
        @Singleton
        @BaseRegistry
        fun providesBaseEngineRegistry(
            factory: EngineRegistry.Factory,
            config: SchemaConfiguration,
        ): EngineRegistry {
            return factory.create(config)
        }

        /**
         * Exposes the ViaductSchema publicly - this is what most components need.
         */
        @Provides
        @Singleton
        @Exposed
        fun providesFullViaductSchema(
            @BaseRegistry engineRegistry: EngineRegistry
        ): ViaductSchema {
            return engineRegistry.getSchema(SchemaId.Full)
        }

        /**
         * Exposes the fully initialized EngineRegistry publicly.
         * This is for components that need full execution functionality.
         */
        @Provides
        @Singleton
        @Exposed
        fun providesEngineRegistry(
            @BaseRegistry registry: EngineRegistry,
            engineFactory: Engine.Factory,
        ): EngineRegistry {
            registry.setEngineFactory(engineFactory)
            return registry
        }
    }

    /**
     * Provides CheckerExecutorFactory for this schema.
     * Injects ViaductSchema directly since that's all we need.
     * Creator function comes from parent injector.
     * @Singleton here means schema-scoped since this runs in a child injector.
     */
    @Provides
    @Singleton
    fun providesCheckerExecutorFactory(
        schema: ViaductSchema, // Direct injection of what we actually need
        creator: CheckerExecutorFactoryCreator, // From parent injector
    ): CheckerExecutorFactory {
        return creator.create(schema)
    }

    @Provides
    @Singleton
    fun providesExecutorValidator(schema: ViaductSchema): ExecutorValidator {
        return ExecutorValidator(
            nodeResolverValidator = Validator.Unvalidated,
            fieldResolverExecutorValidator = ResolverSelectionSetsAreProperlyTyped(schema),
            requiredSelectionsValidator = listOf(
                RequiredSelectionsAreSchematicallyValid(schema),
                RequiredSelectionsAreAcyclic(schema),
                FromArgumentVariablesHaveValidPaths(schema),
                FromFieldVariablesHaveValidPaths(schema)
            ).flatten(),
            fieldCheckerExecutorValidator = CheckerSelectionSetsAreProperlyTyped(schema),
        )
    }

    @Provides
    @Singleton
    fun providesDispatcherRegistry(
        validator: ExecutorValidator,
        checkerExecutorFactory: CheckerExecutorFactory,
        schema: ViaductSchema, // Direct injection of what we actually need
        tenantBootstrapper: TenantAPIBootstrapper, // From parent injector
        @Suppress("UNUSED_PARAMETER") flagManager: FlagManager,
    ): DispatcherRegistry {
        log.info("Creating DispatcherRegistry for Viaduct Modern")
        val startTime = System.currentTimeMillis()
        val dispatcherRegistry = DispatcherRegistryFactory(tenantBootstrapper, validator, checkerExecutorFactory).create(schema)
        val elapsedTime = System.currentTimeMillis() - startTime
        log.info("Created DispatcherRegistry for Viaduct Modern after [$elapsedTime] ms")
        return dispatcherRegistry
    }
}
