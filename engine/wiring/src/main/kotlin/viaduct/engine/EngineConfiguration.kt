@file:Suppress("DEPRECATION")

package viaduct.engine

import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.instrumentation.Instrumentation
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.TemporaryBypassAccessCheck
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.ViaductFragmentLoader
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.ViaductDataFetcherExceptionHandler
import viaduct.engine.runtime.fragment.ViaductExecutableFragmentParser
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

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
    val temporaryBypassAccessCheck: TemporaryBypassAccessCheck = TemporaryBypassAccessCheck.Default,
    val resolverErrorReporter: ErrorReporter = ErrorReporter.NOOP,
    val resolverErrorBuilder: ResolverErrorBuilder = ResolverErrorBuilder.NOOP,
    val dataFetcherExceptionHandler: DataFetcherExceptionHandler = ViaductDataFetcherExceptionHandler(
        ErrorReporter.NOOP,
        ResolverErrorBuilder.NOOP,
    ),
    val meterRegistry: MeterRegistry? = null,
    val additionalInstrumentation: Instrumentation? = null,
    val chainInstrumentationWithDefaults: Boolean = false,
    val resolverInstrumentation: ViaductResolverInstrumentation = ViaductResolverInstrumentation.DEFAULT,
    val globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault
) {
    companion object {
        val default = EngineConfiguration()
    }
}
