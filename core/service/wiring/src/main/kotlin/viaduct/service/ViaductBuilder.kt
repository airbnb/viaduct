package viaduct.service

import io.micrometer.core.instrument.MeterRegistry
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.scoping.SchemaScopeDefinitions
import viaduct.scoping.SchemaScoping
import viaduct.scoping.SchemaScopingManifest
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.TenantModuleInjectorFactory
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
 *     .withTenantModuleInjectorFactory(myInjectorFactory)
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
     * Configures the [TenantModuleInjectorFactory] used to provide per-tenant code injectors.
     * The factory is called once per tenant during startup with the tenant name and the
     * `@TenantBootstrapper`-annotated class from the tenant's config file (or `null` if absent).
     * After all bootstrap calls complete successfully, [TenantModuleInjectorFactory.finalize]
     * is called before any returned [viaduct.service.api.spi.CodeInjector] is used.
     */
    fun withTenantModuleInjectorFactory(tenantModuleInjectorFactory: TenantModuleInjectorFactory) =
        apply {
            builder.withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
        }

    /** Configures the [FlagManager] for controlling framework feature flags. */
    fun withFlagManager(flagManager: FlagManager) =
        apply {
            builder.withFlagManager(flagManager)
        }

    /**
     * Configures executable views of a scope-aware schema from [SchemaScopeInfo] descriptors,
     * discovering the schema from classpath resources.
     *
     * Only the listed views are registered. Include [SchemaScopeInfo.Base] to allow execution
     * using [SchemaId.Base].
     *
     * @param scopedSchemas the views to register; must not be empty.
     * @throws IllegalArgumentException if [scopedSchemas] is empty.
     */
    @Suppress("DEPRECATION")
    fun withScopedSchemas(scopedSchemas: List<SchemaScopeInfo>) =
        apply {
            require(scopedSchemas.isNotEmpty()) {
                "withScopedSchemas requires at least one view to register. Pass " +
                    "SchemaScopeInfo.Base to serve the unscoped schema."
            }
            val schemaConfiguration = SchemaConfiguration.fromResources(
                scopes = scopedSchemas.map { it.toScopeConfig() }.toSet()
            )
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
     * [EngineSchema]. This allows access checks to be wired after schema construction.
     *
     * @deprecated A replacement API using the public Viaduct API (rather than internal engine
     *             types) will be provided in a future release.
     */
    @Deprecated("Will be replaced with a public-API-based checker configuration")
    @VisibleForTest
    fun withCheckerExecutorFactoryCreator(factoryCreator: (EngineSchema) -> CheckerExecutorFactory) =
        apply {
            builder.withCheckerExecutorFactoryCreator(factoryCreator)
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
 * Describes an executable view of a schema that declares scopes.
 *
 * The [schemaId] property holds the [SchemaId] that identifies this schema at execution time.
 * Use it when calling [Viaduct.executeAsync] or [Viaduct.execute] to select this schema.
 *
 * When configuring views explicitly, include [Base] to allow execution using [SchemaId.Base].
 * Builders without explicit view configuration register [Base] by default.
 */
@StableApi
sealed interface SchemaScopeInfo {
    val schemaId: SchemaId

    /**
     * Represents the base view using [SchemaId.Base].
     *
     * The base view contains all non-tenant-local fields without filtering to specific scopes.
     */
    data object Base : SchemaScopeInfo {
        override val schemaId: SchemaId = SchemaId.Base
    }

    /** Registers a named schema projected to the non-empty set of [scopesToApply]. */
    data class Scoped(
        val id: String,
        val scopesToApply: Set<String>,
    ) : SchemaScopeInfo {
        init {
            require(id.isNotBlank()) { "schema id must not be blank" }
            require(scopesToApply.isNotEmpty()) { "scoped schemas must contain at least one scope ID" }
        }

        override val schemaId: SchemaId = SchemaId.Scoped(id, scopesToApply)
    }

    @StableApi
    @OptIn(ExperimentalApi::class, InternalApi::class)
    companion object {
        /**
         * Returns every scoped schema the application declared in its `scopes.yaml` and the build
         * validated, ordered by id.
         *
         * Read these rather than restating them: a deployment usually serves a subset, and selecting from
         * what the build declared is what keeps a renamed scope id from drifting silently. [Base] is not
         * declarable, so add it yourself to also serve the unscoped view.
         *
         * @throws IllegalStateException if no definitions are on the classpath, which is the state of an
         *  application without a `scopes.yaml`, or if two applications contribute them to one classpath.
         * @throws IllegalArgumentException if the definitions on the classpath are malformed or written at an
         *  unsupported version.
         */
        @JvmStatic
        fun fromResources(): List<SchemaScopeInfo> = declaredIn(SchemaScopingManifest.load())

        /**
         * Returns the declared scoped schemas named by [ids], ordered by id. Pass an empty [ids] to
         * select none; add [Base] to serve the unscoped view.
         *
         * @throws IllegalArgumentException if any of [ids] is not declared, naming every one of them.
         * @throws IllegalStateException under the conditions described on the no-argument overload.
         */
        @JvmStatic
        fun fromResources(ids: Set<String>): List<SchemaScopeInfo> = select(declaredIn(SchemaScopingManifest.load()), ids)

        @VisibleForTest
        internal fun declaredIn(scoping: SchemaScoping?): List<SchemaScopeInfo> {
            checkNotNull(scoping) {
                "No schema scope definitions found on the classpath at ${SchemaScopingManifest.RESOURCE_PATH}. " +
                    "Declare them in this application's ${SchemaScopeDefinitions.SOURCE_FILE_NAME}, or register " +
                    "views explicitly with ViaductBuilder.withScopedSchemas."
            }
            return scoping.scopedSchemas.toSortedMap().map { (id, scopes) -> Scoped(id, scopes) }
        }

        @VisibleForTest
        internal fun select(
            declared: List<SchemaScopeInfo>,
            ids: Set<String>,
        ): List<SchemaScopeInfo> {
            val declaredIds = declared.map { it.schemaId.id }
            val undeclared = ids - declaredIds.toSet()
            require(undeclared.isEmpty()) {
                "Scoped schema id(s) ${undeclared.sorted()} are not declared in this application's " +
                    "${SchemaScopeDefinitions.SOURCE_FILE_NAME}. Declared: $declaredIds."
            }
            return declared.filter { it.schemaId.id in ids }
        }
    }
}

internal fun SchemaScopeInfo.toScopeConfig(): SchemaConfiguration.ScopeConfig =
    when (this) {
        SchemaScopeInfo.Base -> SchemaConfiguration.ScopeConfig.Base
        is SchemaScopeInfo.Scoped -> SchemaConfiguration.ScopeConfig.Scoped(id, scopesToApply)
    }
