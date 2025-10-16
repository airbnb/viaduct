package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.instrumentation.Instrumentation
import io.micrometer.core.instrument.MeterRegistry
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
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.ResolverErrorReporter

class StandardViaductModule(
    private val tenantBootstrapper: TenantAPIBootstrapper,
    private val engineConfiguration: EngineConfiguration,
    private val tenantNameResolver: TenantNameResolver,
    private val checkerExecutorFactory: CheckerExecutorFactory?,
    private val checkerExecutorFactoryCreator: CheckerExecutorFactoryCreator?,
    private val documentProviderFactory: DocumentProviderFactory?,
) : AbstractModule() {
    override fun configure() {
        bind(StandardViaduct.Factory::class.java)
            .`in`(Singleton::class.java)

        bind(ExecutableFragmentParser::class.java)
            .to(ViaductExecutableFragmentParser::class.java)
            .`in`(Singleton::class.java)

        bind(EngineConfiguration::class.java).toInstance(engineConfiguration)
        bind(FragmentLoader::class.java).toInstance(engineConfiguration.fragmentLoader)
        bind(CoroutineInterop::class.java).toInstance(engineConfiguration.coroutineInterop)
        bind(FlagManager::class.java).toInstance(engineConfiguration.flagManager)
        bind(DataFetcherExceptionHandler::class.java).toInstance(engineConfiguration.dataFetcherExceptionHandler)
        bind(TemporaryBypassAccessCheck::class.java).toInstance(engineConfiguration.temporaryBypassAccessCheck)
        bind(ResolverErrorReporter::class.java).toInstance(engineConfiguration.resolverErrorReporter)
        bind(ResolverErrorBuilder::class.java).toInstance(engineConfiguration.resolverErrorBuilder)
        bind(TenantAPIBootstrapper::class.java).toInstance(tenantBootstrapper)
        bind(TenantNameResolver::class.java).toInstance(tenantNameResolver)

        val resolvedDocumentProviderFactory =
            documentProviderFactory ?: DocumentProviderFactory { _, _ -> CachingPreparsedDocumentProvider() }
        bind(DocumentProviderFactory::class.java).toInstance(resolvedDocumentProviderFactory)

        val resolvedCheckerExecutorFactoryCreator =
            checkerExecutorFactoryCreator ?: CheckerExecutorFactoryCreator { _ ->
                checkerExecutorFactory ?: NoOpCheckerExecutorFactoryImpl()
            }
        bind(CheckerExecutorFactoryCreator::class.java).toInstance(resolvedCheckerExecutorFactoryCreator)
    }

    @Provides
    @Singleton
    fun provideSchemaFactory(coroutineInterop: CoroutineInterop): SchemaFactory {
        return SchemaFactory(coroutineInterop)
    }

    @Provides
    @Singleton
    fun providesMeterRegistry(): MeterRegistry? {
        return engineConfiguration.meterRegistry
    }

    @Provides
    @Singleton
    @AdditionalInstrumentation
    fun providesInstrumentation(): Instrumentation? {
        return engineConfiguration.additionalInstrumentation
    }
}
