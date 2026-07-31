package viaduct.service.runtime

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.scopes.SchemaScopingMode
import viaduct.graphql.scopes.SchemaView
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.service.api.SchemaId

class SchemaConfiguration private constructor(
    initialFullSchemaConfig: FullSchemaConfig?,
    initialScopedSchemas: Map<SchemaId, ScopedSchemaConfig>
) {
    /**
     * Configuration for registering an executable view derived from the full schema.
     */
    sealed interface ScopeConfig {
        /** Registers the base view. */
        data object Base : ScopeConfig

        /** Registers a named projection containing the selected scope IDs. */
        data class Scoped(
            val id: String,
            val scopeIds: Set<String>,
        ) : ScopeConfig {
            init {
                require(scopeIds.isNotEmpty()) { "Scoped schema configurations must contain at least one scope ID." }
            }
        }
    }

    internal var fullSchemaConfig: FullSchemaConfig? = initialFullSchemaConfig
        private set
    internal val scopedSchemas = ConcurrentHashMap(initialScopedSchemas)

    /**
     * Configuration for building the internal full schema from a source.
     *
     * This represents the "source" step of schema creation - taking raw schema definitions
     * (SDL strings, resource files, or existing schemas) and building a complete executable
     * GraphQL schema.
     *
     * Key characteristics:
     * - Requires a [SchemaFactory] to perform the expensive parsing and schema building
     * - Produces the complete internal schema, including tenant-local fields
     * - Built exactly once per configuration (eager evaluation)
     * - The output serves as input for [ScopedSchemaConfig] instances
     *
     * Implementations:
     * - [FromSdl]: Build from SDL string
     * - [FromResources]: Build from classpath resources
     * - [FromSchema]: Wrap an existing schema
     */
    internal sealed interface FullSchemaConfig {
        fun build(schemaFactory: SchemaFactory): ViaductSchema

        class FromSdl(
            private val sdl: String,
        ) : FullSchemaConfig {
            override fun build(schemaFactory: SchemaFactory): ViaductSchema {
                return schemaFactory.fromSdl(sdl)
            }
        }

        class FromResources(
            private val grtPackagePrefix: String?,
            private val filesIncluded: Regex?,
        ) : FullSchemaConfig {
            override fun build(schemaFactory: SchemaFactory): ViaductSchema {
                return schemaFactory.fromResources(grtPackagePrefix, filesIncluded)
            }
        }

        class FromSchema(
            private val schema: ViaductSchema,
        ) : FullSchemaConfig {
            override fun build(schemaFactory: SchemaFactory): ViaductSchema {
                return schema
            }
        }
    }

    /**
     * Configuration for deriving a named external schema from the full schema.
     *
     * This represents the "transformation" step of schema creation - taking an already-built
     * full schema and applying the explicitly configured schema view.
     *
     * Key characteristics:
     * - Does NOT require a [SchemaFactory] - operates on an already-built [ViaductSchema]
     * - Takes the full schema as input (from [FullSchemaConfig.build])
     * - Only performs fast filtering operations (no parsing or schema building)
     * - Supports lazy evaluation - can defer filtering until schema is first accessed
     * - Produces base or scope-filtered schemas
     *
     * Difference from [FullSchemaConfig]:
     * - [FullSchemaConfig]: Source → Schema (expensive: parsing, building, wiring)
     * - [ScopedSchemaConfig]: Schema → Schema (less expensive: filtering only)
     *
     * Implementations:
     * - [Derived]: Derive from full schema by applying scope filtering
     */
    internal sealed interface ScopedSchemaConfig {
        val schemaId: SchemaId
        val lazy: Boolean

        fun build(fullSchema: ViaductSchema): ViaductSchema

        class Derived(
            private val scopeConfig: ScopeConfig,
            override val lazy: Boolean,
        ) : ScopedSchemaConfig {
            override val schemaId: SchemaId = scopeConfig.schemaId()

            override fun build(fullSchema: ViaductSchema): ViaductSchema {
                val scopedSchema = ScopedSchemaBuilder(
                    inputSchema = fullSchema.schema,
                    additionalVisitorConstructors = emptyList(),
                    scopingMode = scopeConfig.scopingMode(fullSchema),
                ).build(scopeConfig.schemaView()).filtered
                return fullSchema.copy(schema = scopedSchema)
            }
        }
    }

    companion object {
        /**
         * Default configuration that loads the full schema from resources without any scoped schemas.
         */
        val DEFAULT: SchemaConfiguration = fromResources()

        /**
         * Creates a [SchemaConfiguration] that registers schemas from the provided SDL string.
         * Registers one schema for each provided [ScopeConfig] and one base schema.
         * The internal full schema includes all fields; the base schema filters tenant-local fields.
         *
         * @param sdl the GraphQL SDL string defining the schema
         * @param scopes set of [ScopeConfig] defining named external schemas to register
         * @param lazyScopedSchemas if true, named schemas are treated as lazy; otherwise,
         *                          they are computed immediately during initialization.
         * @return a [SchemaConfiguration] with the registered schemas
         */
        fun fromSdl(
            sdl: String,
            scopes: Set<ScopeConfig> = emptySet(),
            lazyScopedSchemas: Boolean = false,
        ): SchemaConfiguration {
            return SchemaConfiguration(
                FullSchemaConfig.FromSdl(sdl),
                scopes.associate {
                    it.schemaId() to ScopedSchemaConfig.Derived(it, lazyScopedSchemas)
                }
            )
        }

        /**
         * Creates a [SchemaConfiguration] that registers schemas by loading them from resources.
         * Registers one schema for each provided [ScopeConfig] and one base schema.
         * The internal full schema includes all fields; the base schema filters tenant-local fields.
         * The resources are loaded from the specified [grtPackagePrefix] and can be filtered using [resourcesIncluded].
         * If [grtPackagePrefix] is null, resources are loaded from the root of the classpath.
         * If [resourcesIncluded] is null, all resources in the package are included.
         *
         * @param grtPackagePrefix optional GRT package prefix to load schema resources from (for testing only)
         * @param resourcesIncluded optional regex to filter which resources to include
         * @param scopes set of [ScopeConfig] defining named external schemas to register
         * @param lazyScopedSchemas if true, named schemas are treated as lazy; otherwise,
         *                          they are computed immediately during initialization.
         * @return a [SchemaConfiguration] with the registered schemas
         */
        fun fromResources(
            grtPackagePrefix: String? = null,
            resourcesIncluded: Regex? = null,
            scopes: Set<ScopeConfig> = emptySet(),
            lazyScopedSchemas: Boolean = false,
        ): SchemaConfiguration {
            return SchemaConfiguration(
                FullSchemaConfig.FromResources(grtPackagePrefix, resourcesIncluded),
                scopes.associate {
                    it.schemaId() to ScopedSchemaConfig.Derived(it, lazyScopedSchemas)
                }
            )
        }

        /**
         * Creates a [SchemaConfiguration] that registers schemas from an existing [ViaductSchema].
         * Registers one schema for each provided [ScopeConfig] and one base schema.
         * The internal full schema includes all fields; the base schema filters tenant-local fields.
         * The provided [schema] is used as the basis for all registered schemas.
         *
         * @param schema the existing [ViaductSchema] to register schemas from
         * @param scopes set of [ScopeConfig] defining named external schemas to register
         * @param lazyScopedSchemas if true, named schemas are treated as lazy; otherwise,
         *                          they are computed immediately during initialization.
         * @return a [SchemaConfiguration] with the registered schemas
         */
        fun fromSchema(
            schema: ViaductSchema,
            scopes: Set<ScopeConfig> = emptySet(),
            lazyScopedSchemas: Boolean = false,
        ): SchemaConfiguration {
            return SchemaConfiguration(
                FullSchemaConfig.FromSchema(schema),
                scopes.associate {
                    it.schemaId() to ScopedSchemaConfig.Derived(it, lazyScopedSchemas)
                }
            )
        }
    }

    // The following classes are used to wrap prebuilt schemas for the deprecated mutable registration method.

    /**
     * Wraps a prebuilt full [ViaductSchema] for use in the deprecated mutable registration method.
     * The schema is provided via a computation block to allow for lazy evaluation if needed.
     */
    private class FromPrebuiltFullSchema(
        private val computeBlock: () -> ViaductSchema
    ) : FullSchemaConfig {
        override fun build(schemaFactory: SchemaFactory): ViaductSchema {
            return computeBlock()
        }
    }

    /**
     * Wraps a prebuilt _scoped_ [ViaductSchema] for use in the deprecated mutable registration method.
     * The schema is provided via a computation block to allow for lazy evaluation if needed.
     */
    private class FromPrebuiltScopedSchema(
        override val schemaId: SchemaId,
        private val computeBlock: () -> ViaductSchema,
        override val lazy: Boolean
    ) : ScopedSchemaConfig {
        override fun build(fullSchema: ViaductSchema): ViaductSchema {
            return computeBlock()
        }
    }

    /**
     * Registers a schema with the given [schemaId]. If a schema with the same ID already exists, it is not replaced.
     * The schema can be provided either as a prebuilt [ViaductSchema] or as a lazy computation block.
     * If [lazy] is true, the schema is computed only when needed.
     * This method is thread-safe.
     *
     * @deprecated This mutable registration method will be removed in favor of immutable configuration.
     * Use the [fromSchema] factory method to create an immutable configuration instead.
     * @param schemaId unique identifier for the schema, can be base or scoped
     * @param scopedSchemaComputeBlock function that returns the [ViaductSchema] when needed
     * @param lazy if true, the schema is computed lazily; otherwise, it is computed immediately
     */
    @Deprecated("DO NOT USE. Airbnb use only. Will be removed in favor of immutable configuration.", level = DeprecationLevel.WARNING)
    fun registerSchema(
        schemaId: SchemaId,
        scopedSchemaComputeBlock: () -> ViaductSchema,
        lazy: Boolean = false,
    ) {
        when (schemaId) {
            is SchemaId.Base -> {
                if (fullSchemaConfig == null) {
                    fullSchemaConfig = FromPrebuiltFullSchema(scopedSchemaComputeBlock)
                }
            }

            is SchemaId.Scoped -> {
                scopedSchemas.putIfAbsent(
                    schemaId,
                    FromPrebuiltScopedSchema(schemaId, scopedSchemaComputeBlock, lazy)
                )
            }
        }
    }
}

/**
 * Creates the explicit schema ID for a named schema configuration.
 *
 * @receiver the scope configuration containing the scope ID and identifiers
 * @return the schema ID representing the configured schema
 */
private fun SchemaConfiguration.ScopeConfig.schemaId(): SchemaId =
    when (this) {
        SchemaConfiguration.ScopeConfig.Base -> SchemaId.Base
        is SchemaConfiguration.ScopeConfig.Scoped -> SchemaId.Scoped(id, scopeIds)
    }

private fun SchemaConfiguration.ScopeConfig.schemaView(): SchemaView =
    when (this) {
        SchemaConfiguration.ScopeConfig.Base -> SchemaView.Base
        is SchemaConfiguration.ScopeConfig.Scoped -> SchemaView.Scoped(scopeIds)
    }

internal fun SchemaConfiguration.ScopeConfig.scopingMode(fullSchema: ViaductSchema): SchemaScopingMode = fullSchema.schemaScopingMode()

/**
 * Converts a [SchemaId.Scoped] to a [SchemaConfiguration.ScopeConfig].
 *
 * @receiver the scoped schema ID containing the scope ID and identifiers
 * @return a ScopeConfig representing the scoped schema configuration
 */
fun SchemaId.Scoped.toScopeConfig(): SchemaConfiguration.ScopeConfig = SchemaConfiguration.ScopeConfig.Scoped(id, scopeIds)
