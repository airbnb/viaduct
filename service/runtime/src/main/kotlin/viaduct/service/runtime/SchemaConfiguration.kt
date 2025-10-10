package viaduct.service.runtime

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.service.api.SchemaId

class SchemaConfiguration private constructor(
    initialFullSchemaConfig: FullSchemaConfig?,
    initialScopedSchemas: Map<SchemaId.Scoped, ScopedSchemaConfig>
) {
    /**
     * Configuration for registering a scoped schema that filters the full schema to specific scope identifiers.
     * Types/fields that are members of scopes are defined using the @scope directive in GraphQL schema definitions.
     *
     * @param id unique identifier used to select this scoped schema when executing operations
     * @param scopeIds set of scope identifiers (from @scope directives) to include in the filtered schema
     */
    data class ScopeConfig(
        val id: String,
        val scopeIds: Set<String>
    )

    internal var fullSchemaConfig: FullSchemaConfig? = initialFullSchemaConfig
        private set
    internal val scopedSchemas = ConcurrentHashMap(initialScopedSchemas)

    /**
     * Configuration for building a full (unfiltered) schema from a source.
     *
     * This represents the "source" step of schema creation - taking raw schema definitions
     * (SDL strings, resource files, or existing schemas) and building a complete executable
     * GraphQL schema.
     *
     * Key characteristics:
     * - Requires a [SchemaFactory] to perform the expensive parsing and schema building
     * - Always produces a schema with ID [SchemaId.Full]
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
            private val packagePrefix: String?,
            private val filesIncluded: Regex?,
        ) : FullSchemaConfig {
            override fun build(schemaFactory: SchemaFactory): ViaductSchema {
                return schemaFactory.fromResources(packagePrefix, filesIncluded)
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
     * Configuration for deriving a scoped (filtered) schema from a full schema.
     *
     * This represents the "transformation" step of schema creation - taking an already-built
     * full schema and applying scope filtering to produce a schema that only includes types
     * and fields annotated with specific scope directives.
     *
     * Key characteristics:
     * - Does NOT require a [SchemaFactory] - operates on an already-built [ViaductSchema]
     * - Takes the full schema as input (from [FullSchemaConfig.build])
     * - Only performs fast filtering operations (no parsing or schema building)
     * - Supports lazy evaluation - can defer filtering until schema is first accessed
     * - Produces schemas with scoped IDs ([SchemaId.Scoped])
     *
     * Difference from [FullSchemaConfig]:
     * - [FullSchemaConfig]: Source → Schema (expensive: parsing, building, wiring)
     * - [ScopedSchemaConfig]: Schema → Schema (less expensive: filtering only)
     *
     * Implementations:
     * - [Derived]: Derive from full schema by applying scope filtering
     */
    internal sealed interface ScopedSchemaConfig {
        val schemaId: SchemaId.Scoped
        val lazy: Boolean

        fun build(fullSchema: ViaductSchema): ViaductSchema

        class Derived(
            override val schemaId: SchemaId.Scoped,
            override val lazy: Boolean,
        ) : ScopedSchemaConfig {
            override fun build(fullSchema: ViaductSchema): ViaductSchema {
                return applyScopes(fullSchema, schemaId)
            }

            private fun applyScopes(
                schema: ViaductSchema,
                scopedId: SchemaId.Scoped
            ): ViaductSchema {
                val scopeIds = scopedId.scopeIds
                if (scopeIds.isEmpty()) {
                    return schema
                }
                val validScopes = schema.scopes()
                if (validScopes.isEmpty()) {
                    return schema
                }
                val scopedSchema = ScopedSchemaBuilder(
                    inputSchema = schema.schema,
                    additionalVisitorConstructors = emptyList(),
                    validScopes = validScopes
                ).applyScopes(scopeIds).filtered
                return schema.copy(schema = scopedSchema)
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
         * Registers one schema for each provided [ScopeConfig] and one full schema.
         * The full schema includes all types and fields without any filtering.
         *
         * @param sdl the GraphQL SDL string defining the schema
         * @param scopes set of [ScopeConfig] defining scoped schemas to register
         * @param lazyScopedSchemas if true, scoped schemas are treated as lazy; otherwise,
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
                    it.schemaId() to ScopedSchemaConfig.Derived(it.schemaId(), lazyScopedSchemas)
                }
            )
        }

        /**
         * Creates a [SchemaConfiguration] that registers schemas by loading them from resources.
         * Registers one schema for each provided [ScopeConfig] and one full schema.
         * The full schema includes all types and fields without any filtering.
         * The resources are loaded from the specified [packagePrefix] and can be filtered using [resourcesIncluded].
         * If [packagePrefix] is null, resources are loaded from the root of the classpath.
         * If [resourcesIncluded] is null, all resources in the package are included.
         *
         * @param packagePrefix optional package prefix to load resources from
         * @param resourcesIncluded optional regex to filter which resources to include
         * @param scopes set of [ScopeConfig] defining scoped schemas to register
         * @param lazyScopedSchemas if true, scoped schemas are treated as lazy; otherwise,
         *                          they are computed immediately during initialization.
         * @return a [SchemaConfiguration] with the registered schemas
         */
        fun fromResources(
            packagePrefix: String? = null,
            resourcesIncluded: Regex? = null,
            scopes: Set<ScopeConfig> = emptySet(),
            lazyScopedSchemas: Boolean = false,
        ): SchemaConfiguration {
            return SchemaConfiguration(
                FullSchemaConfig.FromResources(packagePrefix, resourcesIncluded),
                scopes.associate {
                    it.schemaId() to ScopedSchemaConfig.Derived(it.schemaId(), lazyScopedSchemas)
                }
            )
        }

        /**
         * Creates a [SchemaConfiguration] that registers schemas from an existing [ViaductSchema].
         * Registers one schema for each provided [ScopeConfig] and one full schema.
         * The full schema includes all types and fields without any filtering.
         * The provided [schema] is used as the basis for all registered schemas.
         *
         * @param schema the existing [ViaductSchema] to register schemas from
         * @param scopes set of [ScopeConfig] defining scoped schemas to register
         * @param lazyScopedSchemas if true, scoped schemas are treated as lazy; otherwise,
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
                    it.schemaId() to ScopedSchemaConfig.Derived(it.schemaId(), lazyScopedSchemas)
                }
            )
        }
    }

    // The following classes are used to wrap prebuilt schemas for the deprecated mutable registration method.

    /**
     * Wraps a prebuilt _full_ [ViaductSchema] for use in the deprecated mutable registration method.
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
        override val schemaId: SchemaId.Scoped,
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
     * @param schemaId unique identifier for the schema, can be full or scoped
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
            is SchemaId.Full -> {
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
 * Private helper to create a [SchemaId.Scoped] for a scoped schema from its configuration.
 *
 * @receiver the scope configuration containing the scope ID and identifiers
 * @return a SchemaId.Scoped representing the scoped schema
 */
private fun SchemaConfiguration.ScopeConfig.schemaId(): SchemaId.Scoped = SchemaId.Scoped(id, scopeIds)

/**
 * Converts a [SchemaId.Scoped] to a [SchemaConfiguration.ScopeConfig].
 *
 * @receiver the scoped schema ID containing the scope ID and identifiers
 * @return a ScopeConfig representing the scoped schema configuration
 */
fun SchemaId.Scoped.toScopeConfig() = SchemaConfiguration.ScopeConfig(id, scopeIds)
