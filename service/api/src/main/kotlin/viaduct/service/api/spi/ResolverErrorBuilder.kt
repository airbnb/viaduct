package viaduct.service.api.spi

import viaduct.service.api.GraphQLError

/**
 * Interface for building GraphQL errors from exceptions that occur during data fetching.
 */
fun interface ResolverErrorBuilder {
    /**
     * Converts an exception to a list of GraphQL errors.
     *
     * @param throwable The exception that occurred during data fetching.
     * @param errorMetadata Metadata about the error including execution path, field name,
     *                      parent type, operation name, source location, source object,
     *                      context, local context, and component name.
     * @return A list of GraphQL errors, or null if this builder does not handle this exception type.
     *         Returning null allows the framework to try other error builders or use default handling.
     */
    fun exceptionToGraphQLError(
        throwable: Throwable,
        errorMetadata: ErrorReporter.Metadata
    ): List<GraphQLError>?

    companion object {
        /**
         * A no-op implementation that does not handle any exceptions.
         * Use this when you don't need custom error building.
         */
        val NOOP: ResolverErrorBuilder = ResolverErrorBuilder { _, _ -> null }
    }
}
