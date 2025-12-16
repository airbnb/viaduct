package viaduct.service.api.spi

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment

/**
 * Interface for building GraphQL errors from exceptions that occur during data fetching.
 *
 * @deprecated Use [ViaductResolverErrorBuilder] instead for better API stability.
 *             This interface exposes graphql-java types and will be moved to service/runtime in a future version.
 */
@Deprecated(
    message = "Use ViaductResolverErrorBuilder for better API stability. " +
        "This interface exposes graphql-java types.",
    replaceWith = ReplaceWith(
        "ViaductResolverErrorBuilder",
        "viaduct.service.api.spi.ViaductResolverErrorBuilder"
    ),
    level = DeprecationLevel.WARNING
)
interface ResolverErrorBuilder {
    /**
     * Converts an exception to a list of GraphQL errors.
     *
     * @param throwable The exception that occurred.
     * @param dataFetchingEnvironment The environment in which the data fetching occurred.
     * @param errorMetadata Metadata about the error, such as field name, parent type, etc.
     * @return A list of GraphQL errors, or null if the exception is not handled.
     */
    fun exceptionToGraphQLError(
        throwable: Throwable,
        dataFetchingEnvironment: DataFetchingEnvironment,
        errorMetadata: ErrorMetadata
    ): List<GraphQLError>?

    companion object {
        /**
         * A no-op implementation of [ResolverErrorBuilder] that does not handle any exceptions.
         */
        val NoOpResolverErrorBuilder: ResolverErrorBuilder = object : ResolverErrorBuilder {
            override fun exceptionToGraphQLError(
                throwable: Throwable,
                dataFetchingEnvironment: DataFetchingEnvironment,
                errorMetadata: ErrorMetadata
            ): List<GraphQLError>? = null
        }
    }
}
