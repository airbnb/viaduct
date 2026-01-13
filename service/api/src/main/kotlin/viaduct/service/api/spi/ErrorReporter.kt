package viaduct.service.api.spi

import graphql.schema.DataFetchingEnvironment
import viaduct.graphql.SourceLocation

/**
 * Interface for reporting errors that occur during GraphQL resolver execution.
 */
fun interface ErrorReporter {
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
    fun reportResolverError(
        exception: Throwable,
        errorMessage: String,
        metadata: Metadata
    )

    /**
     * Metadata about a resolver error including execution context.
     *
     * This class encapsulates various details about the error, such as the field name, parent type,
     * operation name, whether it is a framework error, the resolvers involved, and execution context.
     */
    data class Metadata(
        /**
         * The name of the field where the error occurred.
         */
        val fieldName: String? = null,
        /**
         * The type of the parent object where the error occurred.
         */
        val parentType: String? = null,
        /**
         * The name of the operation where the error occurred.
         */
        val operationName: String? = null,
        /**
         * Indicates whether the error is a framework error or caused by a tenant.
         */
        val isFrameworkError: Boolean? = null,
        /**
         * The list of resolvers involved in the error, represented as a string of the class name.
         *
         * Example: "MyCustomTypeResolver"
         */
        val resolvers: List<String>? = null,
        /**
         * The execution path to the field where the error occurred.
         */
        val executionPath: List<Any>? = null,
        /**
         * Source location in the GraphQL query document where the field was requested.
         */
        val sourceLocation: SourceLocation? = null,
        /**
         * The source object being resolved (the parent object).
         */
        val source: Any? = null,
        /**
         * The GraphQL context containing request-level data.
         */
        val context: Any? = null,
        /**
         * The local context for field-specific data.
         */
        val localContext: Any? = null,
        /**
         * The component name associated with the field definition.
         */
        val componentName: String? = null,
        /**
         * The DataFetchingEnvironment from graphql-java.
         *
         * @deprecated Use the individual metadata fields instead (fieldName, parentType, context, etc.).
         *             This property exists only to ease migration and will be removed in a future version.
         *             Access request context via `metadata.context as? GraphQLContext` instead.
         */
        @Deprecated(
            message = "Use the individual metadata fields instead. " +
                "This property exists only to ease migration and will be removed in a future version.",
            level = DeprecationLevel.WARNING
        )
        val dataFetchingEnvironment: DataFetchingEnvironment? = null
    ) {
        /**
         * Converts Metadata to a map for backward compatibility.
         * Used by existing code that passes metadata.toMap().
         */
        fun toMap(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            fieldName?.let { map["fieldName"] = it }
            parentType?.let { map["parentType"] = it }
            operationName?.let { map["operationName"] = it }
            isFrameworkError?.let { map["isFrameworkError"] = it.toString() }
            resolvers?.let { map["resolvers"] = it.joinToString(" > ") }
            return map
        }

        override fun toString(): String =
            listOfNotNull(fieldName, parentType, operationName, isFrameworkError, resolvers)
                .joinToString(separator = ", ", prefix = "{", postfix = "}")

        companion object {
            val EMPTY = Metadata()
        }
    }

    companion object {
        /**
         * A no-op implementation that does nothing.
         * Use this when you don't need custom error reporting.
         */
        val NOOP: ErrorReporter = ErrorReporter { _, _, _ -> }
    }
}
