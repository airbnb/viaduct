package viaduct.service.api.spi

/**
 * Interface for building GraphQL errors from exceptions that occur during data fetching.
 *
 * This is Viaduct's modern error building interface that does not depend on graphql-java types.
 * Implementations convert exceptions into [ViaductGraphQLError] instances that will be
 * included in the GraphQL response.
 *
 * Example implementation:
 * ```kotlin
 * class MyResolverErrorBuilder : ViaductResolverErrorBuilder {
 *     override fun exceptionToGraphQLError(
 *         throwable: Throwable,
 *         errorMetadata: ErrorMetadata
 *     ): List<ViaductGraphQLError>? {
 *         return when (throwable) {
 *             is MyCustomException -> listOf(
 *                 ViaductErrorBuilder.newError(errorMetadata)
 *                     .message("Custom error: ${throwable.customMessage}")
 *                     .extension("errorType", "CUSTOM_ERROR")
 *                     .build()
 *             )
 *             else -> null // Return null to use default error handling
 *         }
 *     }
 * }
 * ```
 */
fun interface ViaductResolverErrorBuilder {
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
        errorMetadata: ErrorMetadata
    ): List<ViaductGraphQLError>?

    companion object {
        /**
         * A no-op implementation that does not handle any exceptions.
         * Use this when you don't need custom error building.
         */
        val NoOp: ViaductResolverErrorBuilder = ViaductResolverErrorBuilder { _, _ -> null }
    }
}
