package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import graphql.execution.instrumentation.Instrumentation
import io.micrometer.core.instrument.MeterRegistry
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.CheckerExecutorFactoryCreator
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.NoOpCheckerExecutorFactoryImpl
import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.runtime.execution.TenantNameResolver

/**
 * Immutable configuration object containing all parameters from StandardViaduct.Builder.
 * This configuration is bound in the parent injector and can be injected by child
 * injector modules to create schema-specific components with the right overrides.
 */
data class ViaductBuilderConfiguration(
    val instrumentation: Instrumentation?,
    val fragmentLoader: FragmentLoader?,
    val meterRegistry: MeterRegistry?,
    val tenantNameResolver: TenantNameResolver,
    val chainInstrumentationWithDefaults: Boolean,
    val checkerExecutorFactory: CheckerExecutorFactory?,
    val checkerExecutorFactoryCreator: CheckerExecutorFactoryCreator?,
    val tenantBootstrapper: TenantAPIBootstrapper,
    val documentProviderFactory: DocumentProviderFactory
)

/**
 * Module that binds ViaductBuilderConfiguration in the parent injector
 * and provides derived configuration objects that child modules can inject.
 */
class ViaductBuilderConfigurationModule(
    private val configuration: ViaductBuilderConfiguration
) : AbstractModule() {
    override fun configure() {
        // Bind the configuration for injection
        bind(ViaductBuilderConfiguration::class.java)
            .toInstance(configuration)
    }

    @Provides
    @Singleton
    fun provideCheckerExecutorFactoryCreator(): CheckerExecutorFactoryCreator {
        // Normalize both builder configuration cases to a creator function:
        // 1. If user provided direct factory: wrap it in a creator that ignores schema
        // 2. If user provided creator: use it directly
        // 3. If neither provided: use NoOpCheckerExecutorFactoryImpl
        //
        // This normalization allows child injectors to consistently use the creator pattern
        // while preserving backward compatibility with both configuration approaches.
        return configuration.checkerExecutorFactoryCreator
            ?: CheckerExecutorFactoryCreator { _ ->
                configuration.checkerExecutorFactory ?: NoOpCheckerExecutorFactoryImpl()
            }
    }

    @Provides
    @Singleton
    fun provideTenantBootstrapper(): TenantAPIBootstrapper {
        return configuration.tenantBootstrapper
    }
}
