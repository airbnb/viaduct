package viaduct.service.runtime

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.schema.scopes.ResourceFileSchema
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.service.api.SchemaId

@InternalApi
class SchemaConfiguration private constructor(
    initialFullSchemaConfig: FullSchemaConfig?,
    initialScopedSchemas: Map<String, ScopedSchemaConfig>
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
        val scopeIds: Set<String>
        val lazy: Boolean

        fun build(fullSchema: ViaductSchema): ViaductSchema

        class Derived(
            override val schemaId: SchemaId,
            override val scopeIds: Set<String>,
            override val lazy: Boolean,
        ) : ScopedSchemaConfig {
            override fun build(fullSchema: ViaductSchema): ViaductSchema {
                return applyScopes(fullSchema, scopeIds)
            }

            private fun applyScopes(
                schema: ViaductSchema,
                scopeIds: Set<String>
            ): ViaductSchema {
                if (scopeIds.isEmpty()) {
                    return schema  // FULL semantics — empty scope set means no filtering.
                }
                val validScopes = schema.scopes()
                if (validScopes.isEmpty()) {
                    throw ViaductInvalidConfigurationException(
                        "Scoped schema requested scopes $scopeIds but the loaded SDL defines no @scope directives. " +
                            "Build-time validation (ValidateScopesVisitor) should have caught this — likely the " +
                            "schema artifact on the classpath is from a build that predates the scope configuration."
                    )
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
        private const val SCOPE_CONFIG_RESOURCE_PATH = "META-INF/viaduct/schema-scoping.json"

        /**
         * Default configuration that loads the full schema from resources without any scoped schemas.
         */
        @Suppress("DEPRECATION")
        val DEFAULT: SchemaConfiguration = fromResources()

        /**
         * Creates a [SchemaConfiguration] by reading scope declarations from the META-INF/viaduct/schema-scoping.json
         * file produced by AssembleCentralSchemaTask. Each schemaId in [schemaIds] (except "FULL") is resolved to
         * its declared scope set from the resource file.
         *
         * If the resource file is absent and [schemaIds] contains only "FULL", returns a no-scopes configuration.
         * If the resource file is absent and [schemaIds] contains non-FULL IDs, throws [ViaductSchemaLoadException].
         * If a requested schemaId is not declared in the resource file, throws [ViaductInvalidConfigurationException].
         *
         * @param schemaIds set of schema IDs to register; "FULL" is always implicitly included as the base schema
         * @return a [SchemaConfiguration] with the resolved scope sets, using eager materialization
         */
        fun fromResources(schemaIds: Set<String>): SchemaConfiguration {
            val classLoader = Thread.currentThread().contextClassLoader
                ?: SchemaConfiguration::class.java.classLoader
            return fromResources(schemaIds, classLoader)
        }

        @Suppress("DEPRECATION")
        internal fun fromResources(schemaIds: Set<String>, classLoader: ClassLoader): SchemaConfiguration {
            val resourceStream = classLoader.getResourceAsStream(SCOPE_CONFIG_RESOURCE_PATH)

            if (resourceStream == null) {
                val nonFullIds = schemaIds.filter { it != ResourceFileSchema.FULL_SCHEMA_ID }
                if (nonFullIds.isEmpty()) {
                    return fromResources()
                }
                throw ViaductSchemaLoadException(
                    "$SCOPE_CONFIG_RESOURCE_PATH not found on classpath. " +
                        "Cannot resolve schema IDs: $nonFullIds. " +
                        "Ensure AssembleCentralSchemaTask has run."
                )
            }

            val resourceFileSchema = try {
                resourceStream.use { stream ->
                    ResourceFileSchema.objectMapper().readValue(stream, ResourceFileSchema::class.java)
                }
            } catch (e: Exception) {
                throw ViaductSchemaLoadException(
                    "Failed to parse $SCOPE_CONFIG_RESOURCE_PATH: ${e.message}",
                    e
                )
            }

            if (resourceFileSchema.version != ResourceFileSchema.CURRENT_VERSION) {
                throw ViaductSchemaLoadException(
                    "Incompatible schema-scoping resource version: file is '${resourceFileSchema.version}', " +
                        "this runtime expects '${ResourceFileSchema.CURRENT_VERSION}'. " +
                        "Rebuild the application against the matching Viaduct version."
                )
            }
            // TODO: when CURRENT_VERSION advances past "1", replace strict equality with a
            // version-aware dispatch (sealed-type or a Map<String, Parser>) so old files
            // can still be read against an explicit migration path.

            val scopes = mutableSetOf<ScopeConfig>()
            for (schemaId in schemaIds) {
                if (schemaId == ResourceFileSchema.FULL_SCHEMA_ID) {
                    continue
                }
                val scopeIds = resourceFileSchema.declaredScopedSchemas[schemaId]
                    ?: throw ViaductInvalidConfigurationException(
                        "Schema ID '$schemaId' not found in $SCOPE_CONFIG_RESOURCE_PATH. " +
                            "Declared IDs: ${resourceFileSchema.declaredScopedSchemas.keys}"
                    )
                scopes.add(ScopeConfig(schemaId, scopeIds))
            }

            return fromResources(scopes = scopes, lazyScopedSchemas = false)
        }

        /**
         * Creates a [SchemaConfiguration] by parsing the given JSON string in the same format as the
         * META-INF/viaduct/schema-scoping.json resource file. All non-FULL declared schemas are eagerly
         * registered. Intended for use in tests that want to avoid classpath resource setup.
         */
        @VisibleForTest
        @Suppress("DEPRECATION")
        fun forTesting(resourceJson: String): SchemaConfiguration {
            val resourceFileSchema = try {
                ResourceFileSchema.objectMapper().readValue(resourceJson, ResourceFileSchema::class.java)
            } catch (e: Exception) {
                throw ViaductSchemaLoadException(
                    "Failed to parse schema-scoping JSON for testing: ${e.message}",
                    e
                )
            }

            if (resourceFileSchema.version != ResourceFileSchema.CURRENT_VERSION) {
                throw ViaductSchemaLoadException(
                    "Incompatible schema-scoping resource version: file is '${resourceFileSchema.version}', " +
                        "this runtime expects '${ResourceFileSchema.CURRENT_VERSION}'. " +
                        "Rebuild the application against the matching Viaduct version."
                )
            }

            val scopes = resourceFileSchema.declaredScopedSchemas
                .filterKeys { it != ResourceFileSchema.FULL_SCHEMA_ID }
                .map { (id, scopeIds) -> ScopeConfig(id, scopeIds) }
                .toSet()
            return fromResources(scopes = scopes, lazyScopedSchemas = false)
        }

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
        @Deprecated(
            message = "Provide scopes via fromResources(schemaIds) or forTesting(json) instead of a raw Set<ScopeConfig>. " +
                "The scopes parameter will be removed in a future version.",
            level = DeprecationLevel.WARNING
        )
        fun fromSdl(
            sdl: String,
            scopes: Set<ScopeConfig> = emptySet(),
            lazyScopedSchemas: Boolean = false,
        ): SchemaConfiguration {
            return SchemaConfiguration(
                FullSchemaConfig.FromSdl(sdl),
                scopes.associate {
                    it.id to ScopedSchemaConfig.Derived(SchemaId(it.id), it.scopeIds, lazyScopedSchemas)
                }
            )
        }

        /**
         * Creates a [SchemaConfiguration] that registers schemas by loading them from resources.
         * Registers one schema for each provided [ScopeConfig] and one full schema.
         * The full schema includes all types and fields without any filtering.
         * The resources are loaded from the specified [grtPackagePrefix] and can be filtered using [resourcesIncluded].
         * If [grtPackagePrefix] is null, resources are loaded from the root of the classpath.
         * If [resourcesIncluded] is null, all resources in the package are included.
         *
         * @param grtPackagePrefix optional GRT package prefix to load schema resources from (for testing only)
         * @param resourcesIncluded optional regex to filter which resources to include
         * @param scopes set of [ScopeConfig] defining scoped schemas to register
         * @param lazyScopedSchemas if true, scoped schemas are treated as lazy; otherwise,
         *                          they are computed immediately during initialization.
         * @return a [SchemaConfiguration] with the registered schemas
         */
        @Deprecated(
            message = "Provide scopes via fromResources(schemaIds) instead of a raw Set<ScopeConfig>. " +
                "The scopes parameter will be removed in a future version.",
            replaceWith = ReplaceWith("fromResources(schemaIds)"),
            level = DeprecationLevel.WARNING
        )
        fun fromResources(
            grtPackagePrefix: String? = null,
            resourcesIncluded: Regex? = null,
            scopes: Set<ScopeConfig> = emptySet(),
            lazyScopedSchemas: Boolean = false,
        ): SchemaConfiguration {
            return SchemaConfiguration(
                FullSchemaConfig.FromResources(grtPackagePrefix, resourcesIncluded),
                scopes.associate {
                    it.id to ScopedSchemaConfig.Derived(SchemaId(it.id), it.scopeIds, lazyScopedSchemas)
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
        @Deprecated(
            message = "Provide scopes via fromResources(schemaIds) or forTesting(json) instead of a raw Set<ScopeConfig>. " +
                "The scopes parameter will be removed in a future version.",
            level = DeprecationLevel.WARNING
        )
        fun fromSchema(
            schema: ViaductSchema,
            scopes: Set<ScopeConfig> = emptySet(),
            lazyScopedSchemas: Boolean = false,
        ): SchemaConfiguration {
            return SchemaConfiguration(
                FullSchemaConfig.FromSchema(schema),
                scopes.associate {
                    it.id to ScopedSchemaConfig.Derived(SchemaId(it.id), it.scopeIds, lazyScopedSchemas)
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
        override val schemaId: SchemaId,
        private val computeBlock: () -> ViaductSchema,
        override val lazy: Boolean
    ) : ScopedSchemaConfig {
        override val scopeIds: Set<String> = emptySet()

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
        if (schemaId == SchemaId.Full) {
            if (fullSchemaConfig == null) {
                fullSchemaConfig = FromPrebuiltFullSchema(scopedSchemaComputeBlock)
            }
        } else {
            scopedSchemas.putIfAbsent(
                schemaId.id,
                FromPrebuiltScopedSchema(schemaId, scopedSchemaComputeBlock, lazy)
            )
        }
    }

    /**
     * Returns the set of scope IDs associated with the given schema [id].
     * Returns an empty set for [SchemaId.Full.id] ("FULL"), which always represents the complete schema.
     * Throws [ViaductInvalidConfigurationException] if the [id] is not registered in this configuration.
     *
     * @param id the schema ID to resolve
     * @return the set of scope IDs for the given schema ID, or an empty set for "FULL"
     */
    fun resolveSchemaId(id: String): Set<String> {
        if (id == SchemaId.Full.id) {
            return emptySet()
        }
        val scopeConfig = scopedSchemas[id]
            ?: throw ViaductInvalidConfigurationException(
                "Schema ID '$id' is not registered in this SchemaConfiguration."
            )
        return Collections.unmodifiableSet(scopeConfig.scopeIds)
    }
}
