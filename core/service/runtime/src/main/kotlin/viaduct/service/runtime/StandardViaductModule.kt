@file:Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")

package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.TypeLiteral
import com.google.inject.name.Names
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.instrumentation.Instrumentation
import io.micrometer.core.instrument.MeterRegistry
import viaduct.engine.EngineConfiguration
import viaduct.engine.SchemaFactory
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.CheckerExecutorFactoryCreator
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.NoOpCheckerExecutorFactoryImpl
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.engine.api.spi.TemporaryBypassAccessCheck
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.engine.runtime.fragment.ExecutableFragmentParser
import viaduct.engine.runtime.fragment.ViaductExecutableFragmentParser
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.TenantAPIBootstrapper as BaseTenantAPIBootstrapper
import viaduct.service.api.spi.TenantModuleBootstrapper

class StandardViaductModule(
    private val tenantBootstrapper: TenantAPIBootstrapper,
    private val engineConfiguration: EngineConfiguration,
    private val tenantNameResolver: TenantNameResolver,
    private val checkerExecutorFactory: CheckerExecutorFactory?,
    private val checkerExecutorFactoryCreator: CheckerExecutorFactoryCreator?,
    private val documentProviderFactory: DocumentProviderFactory?,
    private val proxyResolverFactory: ProxyResolverFactory?,
    private val lenientResolverValidation: Boolean = false,
    private val tenantModuleBootstrapper: TenantModuleBootstrapper? = null,
) : AbstractModule() {
    override fun configure() {
        bind(StandardViaduct.Factory::class.java)
            .`in`(Singleton::class.java)

        bind(ExecutableFragmentParser::class.java)
            .to(ViaductExecutableFragmentParser::class.java)
            .`in`(Singleton::class.java)

        bind(EngineConfiguration::class.java).toInstance(engineConfiguration)
        bind(CoroutineInterop::class.java).toInstance(engineConfiguration.coroutineInterop)
        bind(FlagManager::class.java).toInstance(engineConfiguration.flagManager)
        bind(DataFetcherExceptionHandler::class.java).toInstance(engineConfiguration.dataFetcherExceptionHandler)
        @Suppress("DEPRECATION")
        bind(TemporaryBypassAccessCheck::class.java).toInstance(engineConfiguration.temporaryBypassAccessCheck)
        bind(ErrorReporter::class.java).toInstance(engineConfiguration.resolverErrorReporter)
        bind(ResolverErrorBuilder::class.java).toInstance(engineConfiguration.resolverErrorBuilder)
        bind(object : TypeLiteral<BaseTenantAPIBootstrapper<LegacyTenantModuleBootstrapper>>() {}).toInstance(tenantBootstrapper)
        bind(TenantNameResolver::class.java).toInstance(tenantNameResolver)
        bind(ViaductResolverInstrumentation::class.java).toInstance(engineConfiguration.resolverInstrumentation)

        val resolvedDocumentProviderFactory =
            documentProviderFactory ?: DocumentProviderFactory { _, _ -> CachingPreparsedDocumentProvider() }
        bind(DocumentProviderFactory::class.java).toInstance(resolvedDocumentProviderFactory)

        val resolvedCheckerExecutorFactoryCreator =
            checkerExecutorFactoryCreator ?: CheckerExecutorFactoryCreator { _ ->
                checkerExecutorFactory ?: NoOpCheckerExecutorFactoryImpl()
            }
        bind(CheckerExecutorFactoryCreator::class.java).toInstance(resolvedCheckerExecutorFactoryCreator)

        bind(ProxyResolverFactory::class.java).toInstance(proxyResolverFactory ?: ProxyResolverFactory.NO_OP)

        bindConstant().annotatedWith(Names.named("lenientResolverValidation")).to(lenientResolverValidation)

        if (tenantModuleBootstrapper != null) {
            bind(TenantModuleBootstrapper::class.java).toInstance(tenantModuleBootstrapper)
        }
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
