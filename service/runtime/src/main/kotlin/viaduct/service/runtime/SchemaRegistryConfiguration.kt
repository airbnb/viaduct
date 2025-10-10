package viaduct.service.runtime

import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.github.classgraph.ClassGraph
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import viaduct.engine.ViaductWiringFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.graphql.Scalars
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.graphql.utils.DefaultSchemaProvider
import viaduct.utils.slf4j.logger

class ViaductSchemaLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SchemaRegistryConfiguration {
    private var graphQLSchemaFactory: GraphQLSchemaFactory = GraphQLSchemaFactory.FromRuntimeFiles()
    private var publicSchemaRegistration = mutableListOf<PublicSchemaRegistration>()
    private var asyncSchemaRegistration = ConcurrentHashMap<String, AsyncScopedSchema>()
    private var fullSchema: ViaductSchema? = null

    /**
     * Configuration for registering a scoped schema that filters the full schema to specific scope identifiers.
     * Types/fields that are members of scopes are defined using the @scope directive in GraphQL schema definitions.
     *
     * @param schemaId unique identifier used to select this scoped schema when executing operations
     * @param scopeIds set of scope identifiers (from @scope directives) to include in the filtered schema
     */
    data class ScopeConfig(
        val schemaId: String,
        val scopeIds: Set<String>
    )

    companion object {
        /**
         * Create configuration with a pre-built schema, optionally registering it with given IDs.
         *
         * @param schema the pre-built ViaductSchema instance to use
         * @param scopes set of scope configurations to register as scoped schemas
         * @param fullSchemaIds list of schema IDs to register the full schema under (defaults to empty ID if not provided)
         * @return configured SchemaRegistryConfiguration instance
         */
        fun fromSchema(
            schema: ViaductSchema,
            scopes: Set<ScopeConfig> = emptySet(),
            fullSchemaIds: List<String> = emptyList(),
        ): SchemaRegistryConfiguration {
            val config = SchemaRegistryConfiguration()
            config.graphQLSchemaFactory = GraphQLSchemaFactory.Defined(schema)
            registerSchemas(config, scopes, fullSchemaIds)
            return config
        }

        /**
         * Load schema from SDL, optionally registering with given IDs.
         *
         * @param sdl the GraphQL Schema Definition Language string to parse
         * @param scopes set of scope configurations to register as scoped schemas
         * @param customScalars optional list of custom scalar types to include in the schema
         * @param fullSchemaIds list of schema IDs to register the full schema under (defaults to empty ID if not provided)
         * @return configured SchemaRegistryConfiguration instance
         */
        fun fromSdl(
            sdl: String,
            scopes: Set<ScopeConfig> = emptySet(),
            customScalars: List<GraphQLScalarType>? = null,
            fullSchemaIds: List<String> = emptyList()
        ): SchemaRegistryConfiguration {
            val config = SchemaRegistryConfiguration()
            config.graphQLSchemaFactory = GraphQLSchemaFactory.FromSdl(sdl, customScalars)
            registerSchemas(config, scopes, fullSchemaIds)
            return config
        }

        /**
         * Load schema from resources, optionally registering with given IDs.
         *
         * @param packagePrefix optional package prefix to limit the search scope for GraphQL schema files
         * @param resourcesIncluded optional regex pattern string to match specific resource files (defaults to ".*graphqls")
         * @param scopes set of scope configurations to register as scoped schemas
         * @param fullSchemaIds list of schema IDs to register the full schema under (defaults to empty ID if not provided)
         * @return configured SchemaRegistryConfiguration instance
         */
        fun fromResources(
            packagePrefix: String? = null,
            resourcesIncluded: String? = null,
            scopes: Set<ScopeConfig> = emptySet(),
            fullSchemaIds: List<String> = emptyList()
        ): SchemaRegistryConfiguration {
            val config = SchemaRegistryConfiguration()
            config.graphQLSchemaFactory = GraphQLSchemaFactory.FromRuntimeFiles(packagePrefix, resourcesIncluded?.toRegex())
            registerSchemas(config, scopes, fullSchemaIds)
            return config
        }

        private fun registerSchemas(
            config: SchemaRegistryConfiguration,
            scopes: Set<ScopeConfig>,
            fullSchemaIds: List<String>
        ) {
            if (fullSchemaIds.isEmpty()) {
                config.registerFullSchema("") // default schema ID
            } else {
                fullSchemaIds.forEach { schemaId ->
                    config.registerFullSchema(schemaId)
                }
            }
            scopes.forEach {
                config.registerScopedSchema(it.schemaId, it.scopeIds)
            }
        }
    }

    data class AsyncScopedSchema(
        val schemaComputeBlock: () -> ViaductSchema,
        val documentProviderFactory: ((ViaductSchema) -> PreparsedDocumentProvider)?,
        val lazy: Boolean = true
    )

    @Deprecated("Airbnb specific. Do not use.")
    fun registerSchema(
        schemaId: String,
        scopedSchemaComputeBlock: () -> ViaductSchema,
        documentProviderFactory: ((ViaductSchema) -> PreparsedDocumentProvider)? = null,
        lazy: Boolean = false,
    ) {
        asyncSchemaRegistration[schemaId] = AsyncScopedSchema(scopedSchemaComputeBlock, documentProviderFactory, lazy)
    }

    /**
     * This function will be used to register schema scopes from the full schema on the builder.
     *
     * @param scopeIds a set of scopes (the identifiers in @scope directive) to filter the full schema with
     * @param schemaId an identifier used to select the schema against which to run an operation
     */
    private fun registerScopedSchema(
        schemaId: String,
        scopeIds: Set<String>
    ) {
        publicSchemaRegistration.add(PublicSchemaRegistration.ScopedSchema(schemaId, scopeIds))
    }

    /**
     * This function will be used to register the full schema as public, setting the scopeId as the schemaId
     *
     * @param schemaId an identifier used to select the schema against which to run an operation
     */
    private fun registerFullSchema(schemaId: String): SchemaRegistryConfiguration {
        publicSchemaRegistration.add(PublicSchemaRegistration.FullSchema(schemaId))
        return this
    }

    /**
     * Internal method to convert mutable configuration to immutable info.
     * This is called by StandardViaduct.Builder at build time.
     */
    internal fun toInfo(): SchemaRegistryConfigurationInfo {
        return SchemaRegistryConfigurationInfo(
            graphQLSchemaFactory = graphQLSchemaFactory,
            publicSchemaRegistration = publicSchemaRegistration.toList(),
            asyncSchemaRegistration = asyncSchemaRegistration.toMap(),
            fullSchema = fullSchema
        )
    }
}

/**
 * Different strategies to register the public schema
 */
sealed interface PublicSchemaRegistration {
    data class GeneratedSchema(
        val schemaId: String,
        val schema: ViaductSchema,
        val schemaDocumentProvider: PreparsedDocumentProvider
    )

    fun buildPublicSchema(
        fullSchema: ViaductSchema,
        coroutineInterop: CoroutineInterop
    ): GeneratedSchema

    /**
     * Register the schema from a graphqls string
     *
     * @param schemaId an identifier used to select the schema against which to run an operation
     * @param scopeIds a set of scopes (the identifiers in @scope directive) to filter the full schema with
     */
    class ScopedSchema(
        private val schemaId: String,
        private val scopeIds: Set<String>
    ) : PublicSchemaRegistration {
        override fun buildPublicSchema(
            fullSchema: ViaductSchema,
            coroutineInterop: CoroutineInterop
        ): GeneratedSchema {
            val scopes = fullSchema.scopes()
            val scopedSchema = ScopedSchemaBuilder(
                inputSchema = fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                validScopes = scopes
            ).applyScopes(scopeIds).filtered

            return GeneratedSchema(schemaId, ViaductSchema(scopedSchema), NoOpPreparsedDocumentProvider())
        }
    }

    /**
     * Register the schema from a graphqls string
     *
     * @param schemaId an identifier used to select the schema against which to run an operation
     */
    class FullSchema(
        private val schemaId: String
    ) : PublicSchemaRegistration {
        override fun buildPublicSchema(
            fullSchema: ViaductSchema,
            coroutineInterop: CoroutineInterop
        ): GeneratedSchema {
            return GeneratedSchema(schemaId, fullSchema, NoOpPreparsedDocumentProvider())
        }
    }

    /**
     * Register a scoped schema with the given schemaSdl string and the given schemaId
     *
     * @param schemaId an identifier used to select the schema against which to run an operation
     */
    class SchemaFromSdl(
        private val schemaId: String,
        private val schemaSdl: String
    ) : PublicSchemaRegistration {
        override fun buildPublicSchema(
            fullSchema: ViaductSchema,
            coroutineInterop: CoroutineInterop
        ): GeneratedSchema {
            val scopedSchema = schemaFromSdl(schemaSdl, coroutineInterop)
            return GeneratedSchema(schemaId, scopedSchema, NoOpPreparsedDocumentProvider())
        }
    }
}

/**
 * Different strategies to build the GraphQLSchema
 */
sealed interface GraphQLSchemaFactory {
    fun createSchema(coroutineInterop: CoroutineInterop): ViaductSchema

    /**
     * Register the schema from a graphqls string
     *
     * @param sdl the graphqls string to register the schema from
     */
    class FromSdl(
        val sdl: String,
        val customScalars: List<GraphQLScalarType>? = null,
    ) : GraphQLSchemaFactory {
        override fun createSchema(coroutineInterop: CoroutineInterop): ViaductSchema {
            return schemaFromSdl(sdl, coroutineInterop, customScalars)
        }
    }

    /**
     * Register the schema from the graphqls files available during runtime in the classpath
     *
     * @param packagePrefix the package prefix to search for the graphqls files
     * @param filesIncluded the regex to match the files to include
     */
    class FromRuntimeFiles(
        val packagePrefix: String? = null,
        val filesIncluded: Regex? = null,
    ) : GraphQLSchemaFactory {
        companion object {
            private val log by logger()
        }

        override fun createSchema(coroutineInterop: CoroutineInterop): ViaductSchema {
            return schemaFromRuntimeSchemaFiles(
                coroutineInterop,
                filesIncluded ?: Regex(".*graphqls"),
            )
        }

        /**
         * This function is used to get the full schema files available during runtime
         */
        @OptIn(ExperimentalTime::class)
        private fun schemaFromRuntimeSchemaFiles(
            coroutineInterop: CoroutineInterop,
            filesIncluded: Regex,
        ): ViaductSchema {
            val resourceContents = mutableMapOf<String, String>()

            val (resources, elapsedTime) = measureTimedValue {
                ClassGraph().scan().use {
                    it.getResourcesMatchingPattern(filesIncluded.toPattern()).map { res ->
                        val origin = res.classpathElementURI?.toString() ?: res.classpathElementURL?.toString() ?: "unknown"
                        val uniqueKey = "$origin!/${res.path}"

                        val content = res.open().use { stream ->
                            stream.reader(Charsets.UTF_8).readText().trim()
                        }
                        if (content.isEmpty()) {
                            log.warn("Empty schema file found: $uniqueKey")
                        }
                        resourceContents[uniqueKey] = content
                        uniqueKey
                    }
                }
            }
            log.debug(
                "Got {} resources for pattern {} in {}",
                resources.size,
                filesIncluded.toString(),
                elapsedTime
            )

            if (resources.isEmpty()) {
                throw ViaductSchemaLoadException(
                    "No GraphQL schema files found matching pattern '$filesIncluded' in package prefix '$packagePrefix'. " +
                        "Please ensure your .graphqls files are available in the classpath."
                )
            }

            val sdl = resourceContents.values.joinToString("\n")
            if (sdl.isBlank()) {
                throw ViaductSchemaLoadException(
                    "All GraphQL schema files are empty. Found files: ${resources.joinToString(", ")}. " +
                        "Please ensure your .graphqls files contain valid GraphQL schema definitions."
                )
            }

            return try {
                schemaFromSdl(
                    sdl,
                    coroutineInterop,
                    listOf(Scalars.BackingData),
                    resourceContents.keys.toList()
                )
            } catch (e: graphql.schema.idl.errors.SchemaProblem) {
                // Let SchemaProblem pass through unchanged - it already contains detailed error info
                throw e
            } catch (e: Exception) {
                throw ViaductSchemaLoadException(
                    "Failed to parse GraphQL schema from files: ${resources.joinToString(", ")}. " +
                        "Original error: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * Register the schema from a given instance of the schema
     *
     * @param schema the schema to register
     */
    class Defined(private val schema: ViaductSchema) : GraphQLSchemaFactory {
        override fun createSchema(coroutineInterop: CoroutineInterop): ViaductSchema {
            return schema
        }
    }
}

/**
 * This function is used to get the schema from a graphqls string
 */
private fun schemaFromSdl(
    sdl: String,
    coroutineInterop: CoroutineInterop,
    customScalars: List<GraphQLScalarType>? = null,
    sourceFiles: List<String>? = null,
): ViaductSchema {
    if (sdl.trim().isEmpty()) {
        val sourceInfo = if (sourceFiles?.isNotEmpty() == true) {
            " Source files: ${sourceFiles.joinToString(", ")}"
        } else {
            ""
        }
        throw ViaductSchemaLoadException(
            "GraphQL schema SDL is empty or contains only whitespace.$sourceInfo " +
                "Please provide a valid GraphQL schema definition."
        )
    }

    val tdr = try {
        SchemaParser().parse(sdl)
    } catch (e: Exception) {
        val sourceInfo = if (sourceFiles?.isNotEmpty() == true) {
            " Source files: ${sourceFiles.joinToString(", ")}"
        } else {
            ""
        }
        throw ViaductSchemaLoadException(
            "Failed to parse GraphQL schema.$sourceInfo Original error: ${e.message}",
            e
        )
    }

    // Add default Viaduct schema components
    try {
        DefaultSchemaProvider.addDefaults(tdr)
    } catch (e: Exception) {
        throw ViaductSchemaLoadException(
            "Failed to add default schema components.",
            e
        )
    }

    val definedScalars = DefaultSchemaProvider.defaultScalars() + (customScalars ?: emptySet())
    val actualWiringFactory = ViaductWiringFactory(coroutineInterop)
    val wiring = RuntimeWiring.newRuntimeWiring().wiringFactory(actualWiringFactory).apply {
        definedScalars.forEach { scalar(it) }
    }.build()

    // Let SchemaProblem and other GraphQL validation errors pass through
    return ViaductSchema(SchemaGenerator().makeExecutableSchema(tdr, wiring))
}
