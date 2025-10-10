package viaduct.service.runtime

import graphql.execution.preparsed.PreparsedDocumentProvider
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.SchemaId

/**
 * A factory for creating [PreparsedDocumentProvider] instances for a given schema.
 *
 * @param schemaId The identifier for the schema (may contain scope metadata in service layer)
 * @param schema The ViaductSchema instance
 * @return A PreparsedDocumentProvider configured for this schema
 */
fun interface DocumentProviderFactory {
    fun create(
        schemaId: SchemaId,
        schema: ViaductSchema
    ): PreparsedDocumentProvider
}
