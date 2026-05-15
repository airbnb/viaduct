@file:Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION") // for imports of legacy bootstrap shim

package viaduct.service

import io.micrometer.core.instrument.MeterRegistry
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.StableApi
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.BootstrapperFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.service.api.SchemaId
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
    @Suppress("DEPRECATION")
    fun withTenantModuleBootstrapper(tenantModuleBootstrapper: TenantModuleBootstrapper) =
        apply {
            val bootstrapperBuilder = object : TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> {
                override fun create() = BootstrapperFactory.fromResources(tenantModuleBootstrapper)
            }
            builder.withTenantAPIBootstrapperBuilder(bootstrapperBuilder)
        }

    /** Configures the [FlagManager] for controlling framework feature flags. */
    fun withFlagManager(flagManager: FlagManager) =
        apply {
            builder.withFlagManager(flagManager)
        }

    /**
     * Configures scoped schemas from a list of [SchemaScopeInfo] descriptors.
     * Schema resources are discovered from the classpath.
     *
     * This is a convenience method equivalent to calling [withSchemaConfiguration] with
     * [SchemaConfiguration.fromResources]. The last of [withSchemaConfiguration] or
     * [withScopedSchemas] to be called wins.
     *
     * @deprecated Use [withSchemaConfiguration] with [SchemaConfiguration.fromResources]`(schemaIds)` instead.
     */
    @Deprecated(
        message = "Use withSchemaConfiguration(SchemaConfiguration.fromResources(schemaIds)) instead.",
        replaceWith = ReplaceWith("withSchemaConfiguration(SchemaConfiguration.fromResources(schemaIds))"),
        level = DeprecationLevel.WARNING
    )
    @Suppress("DEPRECATION")
    fun withScopedSchemas(scopedSchemas: List<SchemaScopeInfo>) =
        apply {
            val schemaConfiguration = SchemaConfiguration.fromResources(
                scopes = scopedSchemas.map { it.toScopeConfig() }.toSet()
            )
            builder.withSchemaConfiguration(schemaConfiguration)
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

    /**
     * Configures a factory that creates [CheckerExecutorFactory] instances from a fully-built
     * [ViaductSchema]. This allows access checks to be wired after schema construction.
     *
     * @deprecated A replacement API using the public Viaduct API (rather than internal engine
     *             types) will be provided in a future release.
     */
    @Deprecated("Will be replaced with a public-API-based checker configuration")
    @VisibleForTest
    fun withCheckerExecutorFactoryCreator(factoryCreator: (ViaductSchema) -> CheckerExecutorFactory) =
        apply {
            builder.withCheckerExecutorFactoryCreator(factoryCreator)
        }

    // internal for testing
    @VisibleForTest
    internal fun withTenantAPIBootstrapperBuilder(bootstrapperBuilder: TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper>) =
        apply {
            builder.withTenantAPIBootstrapperBuilder(bootstrapperBuilder)
        }

    // internal for testing
    @VisibleForTest
    internal fun withNoTenantAPIBootstrapper() =
        apply {
            builder.withNoTenantAPIBootstrapper()
        }

    /**
     * Configures a [ProxyResolverFactory] for wrapping resolvers with proxies (e.g., for
     * remote execution). The factory is called for every field and node executor. A non-null
     * return value replaces the original executor.
     */
    @ExperimentalApi
    fun withProxyResolverFactory(proxyResolverFactory: ProxyResolverFactory) =
        apply {
            builder.withProxyResolverFactory(proxyResolverFactory)
        }

    /**
     * When set to true, suppresses the startup error that occurs when a
     * @resolver-annotated field or type has no registered resolver.
     * Default is false (strict: missing resolver = startup error).
     */
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

/**
 * A descriptor for a scoped schema configuration.
 *
 * The [schemaId] property holds the [SchemaId] that identifies this schema at execution time.
 * Use it when calling [Viaduct.executeAsync] or [Viaduct.execute] to select this schema.
 *
 * @param id the name for the scoped schema
 * @param scopesToApply the set of scope-ids that define the scoped schema;
 *        empty means the full (unscoped) schema is registered under this name.
 *
 * @deprecated Use [SchemaConfiguration.fromResources]`(schemaIds)` to configure scoped schemas,
 *             passing the schema IDs directly. [SchemaScopeInfo] will be removed in a future version.
 */
@Deprecated(
    message = "Use SchemaConfiguration.fromResources(schemaIds) to configure scoped schemas directly. " +
        "SchemaScopeInfo will be removed in a future version.",
    level = DeprecationLevel.WARNING
)
@StableApi
class SchemaScopeInfo private constructor(
    val schemaId: SchemaId,
    val scopesToApply: Set<String>,
) {
    constructor(id: String, scopesToApply: Set<String> = emptySet()) : this(
        SchemaId(id),
        scopesToApply
    )

    init {
        require(schemaId.id.isNotBlank()) { "schema id must not be blank" }
    }

    internal fun toScopeConfig(): SchemaConfiguration.ScopeConfig = SchemaConfiguration.ScopeConfig(schemaId.id, scopesToApply)
}
