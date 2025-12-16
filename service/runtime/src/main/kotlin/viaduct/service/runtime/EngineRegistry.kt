@file:OptIn(ExperimentalTime::class)
@file:Suppress("ForbiddenImport")

package viaduct.service.runtime

import graphql.GraphQL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import viaduct.engine.EngineFactory
import viaduct.engine.SchemaFactory
import viaduct.engine.api.Engine
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.SchemaId
import viaduct.utils.collections.parallelMap
import viaduct.utils.slf4j.logger

/**
 * Registry for managing multiple viaduct.engine.Engine instances, each associated with a unique schema identifier.
 * Supports both full and scoped schemas, with optional lazy initialization for scoped schemas.
 */
class EngineRegistry private constructor(
    private val schemasById: Map<SchemaId, Lazy<ViaductSchema>> = emptyMap(),
    private val documentProviderFactory: DocumentProviderFactory,
) {
    /**
     * Exception thrown when a requested schema is not found in the registry.
     * This is an expected failure case that should be handled gracefully by callers.
     */
    class SchemaNotFoundException(
        val schemaId: SchemaId
    ) : Exception("No schema registered for schema ID: $schemaId")

    companion object {
        private val log by logger()
    }

    class Factory
        @Inject
        constructor(
            private val schemaFactory: SchemaFactory,
            private val documentProviderFactory: DocumentProviderFactory,
        ) {
            /**
             * Create a [EngineRegistry] from the provided [SchemaConfiguration].
             * Ensures that a full schema is always registered.
             *
             * This method blocks and thus is safe to call from non-suspending contexts, but may take time to complete.
             *
             * @param config the configuration containing schema registrations
             * @throws IllegalStateException if no full schema is registered.
             * @return a [EngineRegistry] instance with the registered engines
             */
            fun create(config: SchemaConfiguration): EngineRegistry =
                runBlocking(Dispatchers.Default) {
                    createAsync(config)
                }

            /**
             * Create an [EngineRegistry] from the provided [SchemaConfiguration], and suspend while doing so.
             * Ensures that a full schema is always registered.
             * Processes eager scoped schemas in parallel to improve startup time.
             *
             * @param config the configuration containing schema registrations
             * @throws IllegalStateException if no full schema is registered.
             * @return a [EngineRegistry] instance with the registered engines
             */
            @OptIn(ExperimentalCoroutinesApi::class)
            private suspend fun createAsync(config: SchemaConfiguration): EngineRegistry =
                measureTimedValue {
                    log.info("Initializing EngineRegistry...")
                    val fullSchemaConfig = config.fullSchemaConfig
                        ?: throw IllegalStateException("Full schema not registered. This is fatal and should never happen.")
                    // Build full schema eagerly (it's always accessed immediately during injection)
                    val fullSchema = measureTimedValue {
                        log.info("Building full schema...")
                        fullSchemaConfig.build(schemaFactory)
                    }.let { (schema, duration) ->
                        log.info("Full schema built in {} s ({} ms).", duration.inWholeSeconds, duration.inWholeMilliseconds)
                        schema
                    }
                    EngineRegistry(
                        buildMap {
                            put(SchemaId.Full, lazyOf(fullSchema))
                            // block while building scoped schemas in parallel
                            putAll(buildScopedSchemas(config.scopedSchemas.toMap(), fullSchema))
                        },
                        documentProviderFactory,
                    )
                }.let { (registry, duration) ->
                    log.info(
                        "EngineRegistry initialized in {} s ({} ms) with {} schemas ({} eager, {} lazy).",
                        duration.inWholeSeconds,
                        duration.inWholeMilliseconds,
                        registry.schemasById.size,
                        registry.schemasById.count { it.value.isInitialized() },
                        registry.schemasById.count { !it.value.isInitialized() }
                    )
                    registry
                }

            @OptIn(ExperimentalCoroutinesApi::class)
            private suspend fun buildScopedSchemas(
                scopedSchemas: Map<SchemaId, SchemaConfiguration.ScopedSchemaConfig>,
                fullSchema: ViaductSchema
            ): List<Pair<SchemaId, Lazy<ViaductSchema>>> =
                scopedSchemas.entries.parallelMap(parallelWorkers = 4) { (schemaId, scopeConfig) ->
                    schemaId to
                        if (scopeConfig.lazy) {
                            lazy { scopeConfig.build(fullSchema) }.also {
                                log.info("Registered lazy scoped schema for schema ID {}.", schemaId)
                            }
                        } else {
                            log.info("Eagerly building scoped schema for schema ID {}...", schemaId)
                            lazyOf(scopeConfig.build(fullSchema)).also {
                                log.info("Scoped schema for schema ID {} built successfully.", schemaId)
                            }
                        }
                }.toList()
        }

    /**
     * This must be set exactly once, before any calls to [getEngine].
     * We cannot set it in the constructor because of the dependency of [EngineFactory] on the "full" schema. So we must wait for
     * the EngineRegistry to be constructed with the full schema, and then we can construct the [EngineFactory] and set it here.
     */
    private lateinit var engineFactory: EngineFactory

    fun setEngineFactory(engineFactory: EngineFactory) {
        if (::engineFactory.isInitialized) {
            throw IllegalStateException("Engine factory has already been set.")
        }
        this.engineFactory = engineFactory
    }

    private val enginesById = ConcurrentHashMap<SchemaId, Engine>()

    /**
     * Retrieve the set of all registered [SchemaId]s.
     */
    fun getRegisteredSchemaIds(): Set<SchemaId> = schemasById.keys

    /**
     * Retrieve the [Engine] associated with the given [schemaId].
     * If the engine was registered for lazy initialization, a warning is logged.
     *
     * @throws SchemaNotFoundException if no engine is registered for the provided [schemaId].
     * @return the [Engine] instance for the specified [schemaId].
     */
    fun getEngine(schemaId: SchemaId): Engine {
        if (!::engineFactory.isInitialized) {
            throw IllegalStateException("EngineRegistry not fully initialized as engine factory has not been set. This is fatal and should never happen.")
        }
        return enginesById.computeIfAbsent(schemaId) {
            log.info("Initializing engine for schema ID {}", schemaId)
            createEngine(schemaId).also {
                log.info("Engine for schema ID {} initialized successfully.", schemaId)
            }
        }
    }

    /**
     * Retrieve the [ViaductSchema] associated with the given [schemaId].
     *
     * @throws SchemaNotFoundException if no engine is registered for the provided [schemaId].
     * @return the [ViaductSchema] instance for the specified [schemaId].
     */
    fun getSchema(schemaId: SchemaId): ViaductSchema {
        val maybeLazySchema = schemasById[schemaId]
            ?: throw SchemaNotFoundException(schemaId)
        if (!maybeLazySchema.isInitialized()) {
            log.info("Schema for schema ID {} is being initialized lazily. This may take time...", schemaId)
            return measureTimedValue {
                maybeLazySchema.value
            }.let { (schema, duration) ->
                log.info(
                    "Schema for schema ID {} initialized in {} s ({} ms).",
                    schemaId,
                    duration.inWholeSeconds,
                    duration.inWholeMilliseconds
                )
                schema
            }
        }
        return maybeLazySchema.value
    }

    /**
     * **Deprecated:** Do not use. This method is only for temporary use during migration to [Engine] from [GraphQL].
     * Retrieve the underlying [GraphQL] instance associated with the given [schemaId].
     * This method is intended for temporary use during migration and should not be used in new code.
     *
     * @throws IllegalArgumentException if no engine is registered for the provided [schemaId].
     * @throws IllegalStateException if the engine does not directly expose a [GraphQL] instance.
     * @return the [GraphQL] instance for the specified [schemaId].
     */
    @Deprecated("Do not use. This method is only for temporary use during migration to [viaduct.engine.Engine] from [graphql.GraphQL].")
    @Suppress("FunctionName")
    fun getGraphQLEngine_DONOTUSE(schemaId: SchemaId): GraphQL {
        val engine = getEngine(schemaId)
        @Suppress("DEPRECATION")
        return (engine as? viaduct.engine.EngineGraphQLJavaCompat)?.getGraphQL()
            ?: throw IllegalStateException("Engine for schema ID $schemaId does not directly expose the GraphQL object.")
    }

    private fun createEngine(schemaId: SchemaId): Engine {
        val schema = getSchema(schemaId)
        val documentProvider = documentProviderFactory.create(schemaId, schema)
        return engineFactory.create(schema, documentProvider, getSchema(SchemaId.Full))
    }
}
