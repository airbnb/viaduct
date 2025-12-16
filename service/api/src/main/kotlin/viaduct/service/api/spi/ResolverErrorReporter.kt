package viaduct.service.api.spi

import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition

/**
 * Interface for reporting errors that occur during GraphQL resolver execution.
 *
 * This interface allows for custom error reporting strategies, such as logging or sending errors to an external service.
 *
 * @deprecated Use [ViaductResolverErrorReporter] instead for better API stability.
 *             This interface exposes graphql-java types and will be moved to service/runtime in a future version.
 */
@Deprecated(
    message = "Use ViaductResolverErrorReporter for better API stability. " +
        "This interface exposes graphql-java types.",
    replaceWith = ReplaceWith(
        "ViaductResolverErrorReporter",
        "viaduct.service.api.spi.ViaductResolverErrorReporter"
    ),
    level = DeprecationLevel.WARNING
)
fun interface ResolverErrorReporter {
    /**
     * Reports an error that occurred during resolver execution.
     *
     * @param exception The exception that was thrown.
     * @param fieldDefinition The GraphQL field definition where the error occurred.
     * @param dataFetchingEnvironment The data fetching environment in which the error occurred.
     * @param errorMessage A human-readable error message.
     * @param metadata Additional metadata about the error, such as field name, parent type, operation name, etc.
     */
    fun reportError(
        exception: Throwable,
        fieldDefinition: GraphQLFieldDefinition,
        dataFetchingEnvironment: DataFetchingEnvironment,
        errorMessage: String,
        metadata: ErrorMetadata
    )

    companion object {
        /**
         * A no-op implementation of [ResolverErrorReporter] that does nothing.
         */
        val NoOpResolverErrorReporter: ResolverErrorReporter = ResolverErrorReporter { _, _, _, _, _ -> }
    }
}
