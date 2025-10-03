package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import graphql.execution.DataFetcherExceptionHandler
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.fragment.ExecutableFragmentParser
import viaduct.engine.api.fragment.ViaductExecutableFragmentParser
import viaduct.engine.runtime.execution.ViaductDataFetcherExceptionHandler
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.ResolverErrorReporter

/**
 * Module containing truly global utilities and stateless services.
 * These components are shared across all schemas and StandardViaduct instances.
 */
class CoreUtilitiesModule(
    private val flagManager: FlagManager?,
    private val coroutineInterop: CoroutineInterop,
    private val dataFetcherExceptionHandler: DataFetcherExceptionHandler?,
    private val resolverErrorReporter: ResolverErrorReporter,
    private val resolverErrorBuilder: ResolverErrorBuilder
) : AbstractModule() {
    override fun configure() {
        bind(ExecutableFragmentParser::class.java)
            .to(ViaductExecutableFragmentParser::class.java)
            .`in`(Singleton::class.java)
    }

    @Provides
    @Singleton
    fun provideCoroutineInterop(): CoroutineInterop {
        return coroutineInterop
    }

    @Provides
    @Singleton
    fun provideFlagManager(): FlagManager {
        return flagManager ?: FlagManager.default
    }

    @Provides
    @Singleton
    fun provideDataFetcherExceptionHandler(): DataFetcherExceptionHandler {
        return dataFetcherExceptionHandler ?: ViaductDataFetcherExceptionHandler(
            resolverErrorReporter,
            resolverErrorBuilder
        )
    }
}
