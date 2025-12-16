package viaduct.service.api.spi

/**
 * Interface for reporting errors that occur during GraphQL resolver execution.
 *
 * This is Viaduct's modern error reporting interface that does not depend on graphql-java types.
 * Implementations can log errors, send them to external monitoring systems (like Sentry), or
 * perform any other side effects needed for error tracking.
 *
 * Example implementation:
 * ```kotlin
 * class MySentryErrorReporter : ViaductResolverErrorReporter {
 *     override fun reportError(
 *         exception: Throwable,
 *         errorMessage: String,
 *         metadata: ErrorMetadata
 *     ) {
 *         val graphQLContext = metadata.context as? GraphQLContext
 *         val containerRequestContext = graphQLContext?.containerRequestContext
 *
 *         Sentry.captureException(exception) {
 *             it.setExtra("fieldName", metadata.fieldName)
 *             it.setExtra("parentType", metadata.parentType)
 *             it.setExtra("executionPath", metadata.executionPath.toString())
 *             it.setExtra("operationName", metadata.operationName)
 *             it.setExtra("componentName", metadata.componentName)
 *             it.setExtra("requestId", containerRequestContext?.requestId)
 *         }
 *     }
 * }
 * ```
 */
fun interface ViaductResolverErrorReporter {
    /**
     * Reports an error that occurred during resolver execution.
     *
     * @param exception The exception that was thrown during data fetching.
     * @param errorMessage A human-readable error message describing what went wrong.
     * @param metadata Metadata about the error including execution path, field name, parent type,
     *                 operation name, source location, source object, context (for accessing
     *                 GraphQLContext/containerRequestContext), local context (for detecting
     *                 derived fields/suboperations), and component name.
     */
    fun reportError(
        exception: Throwable,
        errorMessage: String,
        metadata: ErrorMetadata
    )

    companion object {
        /**
         * A no-op implementation that does nothing.
         * Use this when you don't need custom error reporting.
         */
        val NoOp: ViaductResolverErrorReporter = ViaductResolverErrorReporter { _, _, _ -> }
    }
}
