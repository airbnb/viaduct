package viaduct.engine.api.execution

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment

/**
 * Interface for building GraphQL errors from exceptions that occur during data fetching.q
 */
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
        errorMetadata: ResolverErrorReporter.Companion.ErrorMetadata
    ): List<GraphQLError>?

    companion object {
        /**
         * A no-op implementation of [ResolverErrorBuilder] that does not handle any exceptions.
         */
        val NoOpResolverErrorBuilder: ResolverErrorBuilder = object : ResolverErrorBuilder {
            override fun exceptionToGraphQLError(
                throwable: Throwable,
                dataFetchingEnvironment: DataFetchingEnvironment,
                errorMetadata: ResolverErrorReporter.Companion.ErrorMetadata
            ): List<GraphQLError>? = null
        }
    }
}
