package viaduct.service

import io.micrometer.core.instrument.MeterRegistry
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.StableApi
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.TenantModuleBootstrapper
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
 *     .withTenantModuleBootstrapper(myBootstrapper)
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
    private val builder = StandardViaduct.Builder()

    /**
     * Configures the [TenantModuleBootstrapper] used to provide per-tenant bootstrapping.
     * The bootstrapper is called once per tenant during startup with the tenant name and the
     * `@TenantBootstrapper`-annotated class from the tenant's config file (or `null` if absent).
     * After all bootstrap calls complete successfully, [TenantModuleBootstrapper.finalize]
     * is called before any returned [viaduct.service.api.spi.CodeInjector] is used.
     */
    fun withTenantModuleBootstrapper(tenantModuleBootstrapper: TenantModuleBootstrapper) =
        apply {
            builder.withTenantModuleBootstrapper(tenantModuleBootstrapper)
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

    /** @see StandardViaduct.Builder.withCheckerExecutorFactoryCreator */
    @VisibleForTest
    fun withCheckerExecutorFactoryCreator(factoryCreator: (ViaductSchema) -> CheckerExecutorFactory) =
        apply {
            builder.withCheckerExecutorFactoryCreator(factoryCreator)
        }

    /** @see StandardViaduct.Builder.withTenantAPIBootstrapperBuilder */
    @VisibleForTest
    fun withTenantAPIBootstrapperBuilder(bootstrapperBuilder: TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper>) =
        apply {
            builder.withTenantAPIBootstrapperBuilder(bootstrapperBuilder)
        }

    /** @see StandardViaduct.Builder.withNoTenantAPIBootstrapper */
    @VisibleForTest
    fun withNoTenantAPIBootstrapper() =
        apply {
            builder.withNoTenantAPIBootstrapper()
        }

    /** @see StandardViaduct.Builder.withProxyResolverFactory */
    @ExperimentalApi
    fun withProxyResolverFactory(proxyResolverFactory: ProxyResolverFactory) =
        apply {
            builder.withProxyResolverFactory(proxyResolverFactory)
        }

    /** @see StandardViaduct.Builder.withLenientResolverValidation */
    fun withLenientResolverValidation(lenient: Boolean = true) =
        apply {
            builder.withLenientResolverValidation(lenient)
        }

    /**
     * Builds and returns a [Viaduct] instance ready to execute GraphQL operations.
     *
     * @return a [Viaduct] instance configured with the supplied SPI implementations
     */
    fun build(): Viaduct = builder.build()
}
