package viaduct.service

import graphql.execution.DataFetcherExceptionHandler
import io.micrometer.core.instrument.MeterRegistry
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.ResolverErrorReporter
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class ViaductBuilder {
    val builder = StandardViaduct.Builder()

    /** See [withTenantAPIBootstrapperBuilder]. */
    fun withTenantAPIBootstrapperBuilder(builder: TenantAPIBootstrapperBuilder) =
        apply {
            this.builder.withTenantAPIBootstrapperBuilders(listOf(builder))
        }

    /**
     * Adds a TenantAPIBootstrapperBuilder to be used for creating TenantAPIBootstrapper instances.
     * Multiple builders can be added, and all their TenantAPIBootstrapper instances will be used
     * together to bootstrap tenant modules.
     *
     * @param builders The builder instance that will be used to create a TenantAPIBootstrapper
     * @return This Builder instance for method chaining
     */
    fun withTenantAPIBootstrapperBuilders(builders: List<TenantAPIBootstrapperBuilder>) =
        apply {
            builder.withTenantAPIBootstrapperBuilders(builders)
        }

    /**
     * A convenience function to indicate that no bootstrapper is
     * wanted.  Used for testing purposes.
     * Failing to provide a bootstrapper is an error that should be flagged at build() time.
     */
    fun withNoTenantAPIBootstrapper() =
        apply {
            builder.withTenantAPIBootstrapperBuilders(emptyList())
        }

    fun withFlagManager(flagManager: FlagManager) =
        apply {
            builder.withFlagManager(flagManager)
        }

    /**
     * By default, Viaduct instances implement `Query.node` and `Query.nodes`
     * resolvers automatically.  Calling this function with false turns off that default behavior.
     * (If your schema does not have the `Query.node/s` field(s), you do
     * _not_ have to explicitly turn off the default behavior.)
     */
    fun standardNodeBehavior(standardNodeBehavior: Boolean) =
        apply {
            builder.withoutDefaultQueryNodeResolvers(standardNodeBehavior)
        }

    fun withSchemaConfiguration(schemaConfiguration: SchemaConfiguration) =
        apply {
            builder.withSchemaConfiguration(schemaConfiguration)
        }

    /**
     * Configures the MeterRegistry for metrics collection.
     * This enables observability by tracking metrics such as query execution times,
     * error rates, and other operational metrics.
     *
     * @param meterRegistry The MeterRegistry instance to use for metrics collection
     * @return This Builder instance for method chaining
     */
    fun withMeterRegistry(meterRegistry: MeterRegistry) =
        apply {
            builder.withMeterRegistry(meterRegistry)
        }

    /**
     * Configures the ResolverErrorReporter for error reporting.
     * This enables reporting of resolver errors to external monitoring systems.
     *
     * @param resolverErrorReporter The ResolverErrorReporter instance to use for error reporting
     * @return This Builder instance for method chaining
     */
    fun withResolverErrorReporter(resolverErrorReporter: ResolverErrorReporter) =
        apply {
            builder.withResolverErrorReporter(resolverErrorReporter)
        }

    /**
     * Configures the ResolverErrorBuilder for building custom error responses.
     * This works in conjunction with the ResolverErrorReporter to format error messages.
     *
     * @param resolverErrorBuilder The ResolverErrorBuilder instance to use for building errors
     * @return This Builder instance for method chaining
     */
    fun withDataFetcherErrorBuilder(resolverErrorBuilder: ResolverErrorBuilder) =
        apply {
            builder.withDataFetcherErrorBuilder(resolverErrorBuilder)
        }

    /**
     * Configures the DataFetcherExceptionHandler for handling data fetcher exceptions.
     * This provides custom exception handling logic for errors that occur during data fetching.
     *
     * @param dataFetcherExceptionHandler The DataFetcherExceptionHandler instance to use
     * @return This Builder instance for method chaining
     */
    fun withDataFetcherExceptionHandler(dataFetcherExceptionHandler: DataFetcherExceptionHandler) =
        apply {
            builder.withDataFetcherExceptionHandler(dataFetcherExceptionHandler)
        }

    /**
     * Builds the Guice Module within Viaduct and gets Viaduct from the injector.
     *
     * @return a Viaduct Instance ready to execute
     */
    fun build(): StandardViaduct = builder.build()
}
