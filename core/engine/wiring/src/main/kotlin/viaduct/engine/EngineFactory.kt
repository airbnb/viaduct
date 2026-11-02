@file:Suppress("DEPRECATION")

package viaduct.engine

import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentProvider
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.FullSchema
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.execution.QueryPlanFactory
import viaduct.engine.runtime2.Engine2
import viaduct.service.api.spi.FlagManager

/**
 * Factory for creating Engine instances with specific schema and document caching configurations.
 */
class EngineFactory(
    private val config: EngineConfiguration = EngineConfiguration.default,
    private val dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty,
    private val queryPlanFactory: QueryPlanFactory = QueryPlanFactory.Cached(config.meterRegistry),
) {
    /** The engine kind used when none is specified. */
    val defaultEngineKind: EngineKind
        get() = if (config.flagManager.isEnabled(FlagManager.Flags.ENGINE2_ENABLED)) EngineKind.ENGINE2 else EngineKind.CURRENT

    /** Creates the default engine (see [defaultEngineKind]). */
    fun create(
        schema: EngineSchema,
        documentProvider: PreparsedDocumentProvider = NoOpPreparsedDocumentProvider(),
        fullSchema: FullSchema,
    ): Engine = create(defaultEngineKind, schema, documentProvider, fullSchema)

    /**
     * Creates an engine of the given [kind].
     *
     * @param schema The schema clients see. Requests are validated against it, and introspection answers come from it.
     * @param documentProvider Caches parsed GraphQL documents.
     * @param fullSchema The complete internal schema, including tenant-local fields. Requests execute against it.
     */
    fun create(
        kind: EngineKind,
        schema: EngineSchema,
        documentProvider: PreparsedDocumentProvider = NoOpPreparsedDocumentProvider(),
        fullSchema: FullSchema,
    ): Engine =
        when (kind) {
            EngineKind.ENGINE2 -> Engine2(
                schema = schema,
                fullSchema = fullSchema,
                dispatcherRegistry = dispatcherRegistry,
                documentProvider = documentProvider,
                globalIDCodec = config.globalIDCodec,
                fieldSelectivityProvider = config.fieldSelectivityProvider,
                resolverInstrumentation = config.resolverInstrumentation,
                dataFetcherExceptionHandler = config.dataFetcherExceptionHandler,
                additionalInstrumentation = config.additionalInstrumentation,
                coroutineInterop = config.coroutineInterop,
                resolverRegistryFactory = config.engine2ResolverRegistryFactory,
                usePlanCache = config.flagManager.isEnabled(FlagManager.Flags.ENGINE2_PLAN_CACHE),
            )
            EngineKind.CURRENT -> EngineImpl(
                config,
                dispatcherRegistry,
                schema,
                documentProvider,
                fullSchema,
                queryPlanFactory,
            )
        }
}
