package viaduct.engine

import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentProvider
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.FullSchema
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.execution.QueryPlanFactory
import viaduct.service.api.spi.FlagManager

/**
 * Factory for creating Engine instances with specific schema and document caching configurations.
 */
class EngineFactory(
    private val config: EngineConfiguration = EngineConfiguration.default,
    private val dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty,
    private val queryPlanFactory: QueryPlanFactory = QueryPlanFactory.Cached(config.meterRegistry),
) {
    /**
     * Creates a new Engine instance.
     *
     * @param schema The compiled Viaduct schema to validate against, but not used for execution except for introspection queries.
     * @param documentProvider Provider for preparsed and cached GraphQL documents.
     * @param fullSchema The complete internal Viaduct schema used for execution.
     * @return A configured Engine instance.
     */
    fun create(
        schema: EngineSchema,
        documentProvider: PreparsedDocumentProvider = NoOpPreparsedDocumentProvider(),
        fullSchema: FullSchema,
    ): Engine {
        require(!config.flagManager.isEnabled(FlagManager.Flags.ENGINE2_ENABLED)) {
            "ENGINE2_ENABLED requires the runtime2 engine integration"
        }
        return EngineImpl(
            config,
            dispatcherRegistry,
            schema,
            documentProvider,
            fullSchema,
            queryPlanFactory,
        )
    }
}
