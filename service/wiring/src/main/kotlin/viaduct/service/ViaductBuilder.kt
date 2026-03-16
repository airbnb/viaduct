package viaduct.service

import graphql.execution.DataFetcherExceptionHandler
import io.micrometer.core.instrument.MeterRegistry
import viaduct.apiannotations.StableApi
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

/**
 * Builder for constructing [Viaduct] instances with full control over SPI configuration.
 *
 * This is the fuller-featured API for creating a Viaduct instance, offering fine-grained
 * control over observability, error handling, feature flags, schema configuration, and
 * multi-tenancy. For a simpler alternative with sensible defaults, see [BasicViaductFactory].
 *
 * Typical usage:
 * ```kotlin
 * val viaduct = ViaductBuilder()
 *     .withTenantAPIBootstrapperBuilders(builders)
 *     .withMeterRegistry(meterRegistry)
 *     .withResolverErrorReporter(errorReporter)
 *     .build()
 * ```
 *
 * @see BasicViaductFactory
 * @see Viaduct
 */
@StableApi
class ViaductBuilder {
    val builder = StandardViaduct.Builder()

    /** Convenience for [withTenantAPIBootstrapperBuilders] with a single builder. */
    fun withTenantAPIBootstrapperBuilder(builder: TenantAPIBootstrapperBuilder<TenantModuleBootstrapper>) =
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
    fun withTenantAPIBootstrapperBuilders(builders: List<TenantAPIBootstrapperBuilder<TenantModuleBootstrapper>>) =
        apply {
            builder.withTenantAPIBootstrapperBuilders(builders)
        }

    /**
     * A convenience function to indicate that no bootstrapper is
     * wanted.  Used for testing purposes.
     * Failing to provide a bootstrapper is an error that should be flagged at build() time.
     */
    @VisibleForTest
    fun withNoTenantAPIBootstrapper() =
        apply {
            builder.withTenantAPIBootstrapperBuilders(emptyList())
        }

    /** Configures the [FlagManager] for controlling framework feature flags. */
    fun withFlagManager(flagManager: FlagManager) =
        apply {
            builder.withFlagManager(flagManager)
        }

    /** Configures schema registration, including multi-tenant scoped schemas. */
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
    fun withResolverErrorReporter(resolverErrorReporter: ErrorReporter) =
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
     * Configures the GlobalIDCodec for serializing and deserializing GlobalIDs.
     * All tenant-API implementations within this Viaduct instance will share this codec
     * to ensure interoperability.
     *
     * @param globalIDCodec The GlobalIDCodec instance to use
     * @return This Builder instance for method chaining
     */
    fun withGlobalIDCodec(globalIDCodec: GlobalIDCodec) =
        apply {
            builder.withGlobalIDCodec(globalIDCodec)
        }

    /**
     * Builds and returns a [Viaduct] instance ready to execute GraphQL operations.
     *
     * @return a [Viaduct] instance configured with the supplied SPI implementations
     */
    fun build(): StandardViaduct = builder.build()
}
