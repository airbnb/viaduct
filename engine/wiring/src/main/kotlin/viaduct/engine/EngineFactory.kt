package viaduct.engine

import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentProvider
import viaduct.engine.api.Engine
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.DispatcherRegistry

/**
 * Factory for creating Engine instances with specific schema and document caching configurations.
 */
class EngineFactory(
    private val config: EngineConfiguration = EngineConfiguration.default,
    private val dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty,
) {
    /**
     * Creates a new Engine instance.
     *
     * @param schema The compiled Viaduct schema to validate against, but not used for execution except for introspection queries.
     * @param documentProvider Provider for preparsed and cached GraphQL documents.
     * @param fullSchema The full Viaduct schema used for execution. Defaults to [schema] when not supplied.
     * @return A configured Engine instance.
     */
    fun create(
        schema: ViaductSchema,
        documentProvider: PreparsedDocumentProvider = NoOpPreparsedDocumentProvider(),
        fullSchema: ViaductSchema = schema,
    ): Engine {
        return EngineImpl(
            config,
            dispatcherRegistry,
            schema,
            documentProvider,
            fullSchema,
        )
    }
}
