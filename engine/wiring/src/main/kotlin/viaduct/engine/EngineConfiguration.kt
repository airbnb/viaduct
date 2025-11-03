package viaduct.engine

import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.instrumentation.Instrumentation
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.fragment.ViaductExecutableFragmentParser
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.ViaductFragmentLoader
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.ViaductDataFetcherExceptionHandler
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.ResolverErrorReporter

/**
 * Aggregates the parent-scoped collaborators and tuning knobs used to build [Engine] instances.
 * The parent injector creates one of these per `StandardViaduct`, and every schema-scoped engine
 * reuses it while schema-specific state (such as the [viaduct.engine.runtime.DispatcherRegistry])
 * is supplied separately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
data class EngineConfiguration(
    val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
    val fragmentLoader: FragmentLoader = ViaductFragmentLoader(ViaductExecutableFragmentParser()),
    val flagManager: FlagManager = FlagManager.default,
    @Suppress("DEPRECATION")
    val temporaryBypassAccessCheck: viaduct.engine.api.TemporaryBypassAccessCheck = viaduct.engine.api.TemporaryBypassAccessCheck.Default,
    val resolverErrorReporter: ResolverErrorReporter = ResolverErrorReporter.NoOpResolverErrorReporter,
    val resolverErrorBuilder: ResolverErrorBuilder = ResolverErrorBuilder.NoOpResolverErrorBuilder,
    val dataFetcherExceptionHandler: DataFetcherExceptionHandler = ViaductDataFetcherExceptionHandler(
        ResolverErrorReporter.NoOpResolverErrorReporter,
        ResolverErrorBuilder.NoOpResolverErrorBuilder,
    ),
    val meterRegistry: MeterRegistry? = null,
    val additionalInstrumentation: Instrumentation? = null,
    val chainInstrumentationWithDefaults: Boolean = false,
    val resolverInstrumentation: ViaductResolverInstrumentation = ViaductResolverInstrumentation.DEFAULT
) {
    companion object {
        val default = EngineConfiguration()
    }
}
