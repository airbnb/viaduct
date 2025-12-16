package viaduct.dataloader

import com.google.inject.ImplementedBy

data class DataLoaderInstrumentationArgs(
    val dataLoaderInfo: DataLoader.DataLoaderInfo,
    val statsCollector: DataLoaderStatsCollector?,
)

/**
 * Factory for creating [DataLoaderInstrumentation] instances.
 * Default to [NoopInstrumentationFactory] so that all the tests that initialize DataLoaders via injection can run without issue.
 *
 * Viaduct-specific instrumentation is installed by [com.airbnb.viaduct.module.ViaductDataLoaderModule] which is installed in the ViaductCommonModule
 * which overrides this @ImplementedBy annotation with a specific set of instrumentations.
 */
@ImplementedBy(NoopInstrumentationFactory::class)
interface DataLoaderInstrumentationFactory {
    fun dataLoaderInstrumentation(args: DataLoaderInstrumentationArgs): DataLoaderInstrumentation
}

class NoopInstrumentationFactory : DataLoaderInstrumentationFactory {
    override fun dataLoaderInstrumentation(args: DataLoaderInstrumentationArgs): DataLoaderInstrumentation = DataLoaderInstrumentation.DEFAULT
}
