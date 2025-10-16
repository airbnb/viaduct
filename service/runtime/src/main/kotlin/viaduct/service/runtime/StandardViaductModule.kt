package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.instrumentation.Instrumentation
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.EngineConfiguration
import viaduct.engine.SchemaFactory
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.CheckerExecutorFactoryCreator
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.NoOpCheckerExecutorFactoryImpl
import viaduct.engine.api.TemporaryBypassAccessCheck
import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.fragment.ExecutableFragmentParser
import viaduct.engine.api.fragment.ViaductExecutableFragmentParser
import viaduct.engine.runtime.ViaductFragmentLoader
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.engine.runtime.execution.ViaductDataFetcherExceptionHandler
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.ResolverErrorReporter

class StandardViaductModule(
    private val tenantBootstrapper: TenantAPIBootstrapper,
    private val engineConfiguration: EngineConfiguration,
    private val instrumentation: Instrumentation?,
    private val meterRegistry: MeterRegistry?,
    private val tenantNameResolver: TenantNameResolver,
    private val checkerExecutorFactory: CheckerExecutorFactory?,
    private val checkerExecutorFactoryCreator: CheckerExecutorFactoryCreator?,
    private val documentProviderFactory: DocumentProviderFactory?,
    private val flagManager: FlagManager?,
    private val fragmentLoader: FragmentLoader?,
    private val coroutineInterop: CoroutineInterop?,
    private val dataFetcherExceptionHandler: DataFetcherExceptionHandler?,
    private val resolverErrorReporter: ResolverErrorReporter?,
    private val resolverErrorBuilder: ResolverErrorBuilder?,
    private val temporaryBypassAccessCheck: TemporaryBypassAccessCheck?
) : AbstractModule() {
    override fun configure() {
        bind(StandardViaduct.Factory::class.java)
            .`in`(Singleton::class.java)

        bind(ExecutableFragmentParser::class.java)
            .to(ViaductExecutableFragmentParser::class.java)
            .`in`(Singleton::class.java)
    }

    @Provides
    @Singleton
    @ExperimentalCoroutinesApi
    fun provideFragmentLoader(viaductFragmentLoader: ViaductFragmentLoader): FragmentLoader {
        return fragmentLoader ?: viaductFragmentLoader
    }

    @Provides
    @Singleton
    fun provideCoroutineInterop(): CoroutineInterop {
        return coroutineInterop ?: DefaultCoroutineInterop
    }

    @Provides
    @Singleton
    fun provideFlagManager(): FlagManager {
        return flagManager ?: FlagManager.default
    }

    @Provides
    @Singleton
    fun provideSchemaFactory(coroutineInterop: CoroutineInterop): SchemaFactory {
        return SchemaFactory(coroutineInterop)
    }

    @Provides
    @Singleton
    fun provideDocumentProviderFactory(): DocumentProviderFactory {
        return documentProviderFactory ?: DocumentProviderFactory { _, _ -> CachingPreparsedDocumentProvider() }
    }

    @Provides
    @Singleton
    fun providesEngineConfiguration(): EngineConfiguration {
        return engineConfiguration
    }

    @Provides
    @Singleton
    fun providesMeterRegistry(): MeterRegistry? {
        return meterRegistry
    }

    @Provides
    @Singleton
    @AdditionalInstrumentation
    fun providesInstrumentation(): Instrumentation? {
        return instrumentation
    }

    @Provides
    @Singleton
    fun provideResolverErrorReporter(): ResolverErrorReporter {
        return resolverErrorReporter ?: ResolverErrorReporter.NoOpResolverErrorReporter
    }

    @Provides
    @Singleton
    fun provideResolverErrorBuilder(): ResolverErrorBuilder {
        return resolverErrorBuilder ?: ResolverErrorBuilder.NoOpResolverErrorBuilder
    }

    @Provides
    @Singleton
    fun provideDataFetcherExceptionHandler(
        resolverErrorReporter: ResolverErrorReporter,
        resolverErrorBuilder: ResolverErrorBuilder
    ): DataFetcherExceptionHandler {
        return dataFetcherExceptionHandler ?: ViaductDataFetcherExceptionHandler(
            resolverErrorReporter,
            resolverErrorBuilder
        )
    }

    @Provides
    @Singleton
    fun provideTemporaryBypassChecker(): TemporaryBypassAccessCheck {
        return temporaryBypassAccessCheck ?: TemporaryBypassAccessCheck.Default
    }

    @Provides
    @Singleton
    fun provideCheckerExecutorFactoryCreator(): CheckerExecutorFactoryCreator {
        return checkerExecutorFactoryCreator
            ?: CheckerExecutorFactoryCreator { _ ->
                checkerExecutorFactory ?: NoOpCheckerExecutorFactoryImpl()
            }
    }

    @Provides
    @Singleton
    fun provideTenantBootstrapper(): TenantAPIBootstrapper {
        return tenantBootstrapper
    }
}
