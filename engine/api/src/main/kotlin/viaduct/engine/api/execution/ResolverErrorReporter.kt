package viaduct.engine.api.execution

import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition

/**
 * Interface for reporting errors that occur during GraphQL resolver execution.
 *
 * This interface allows for custom error reporting strategies, such as logging or sending errors to an external service.
 */
fun interface ResolverErrorReporter {
    fun reportError(
        exception: Throwable,
        fieldDefinition: GraphQLFieldDefinition,
        dataFetchingEnvironment: DataFetchingEnvironment,
        errorMessage: String,
        metadata: ErrorMetadata
    )

    companion object {
        /**
         * Metadata about an error that occurred during resolver execution.
         *
         * This class encapsulates various details about the error, such as the field name, parent type,
         * operation name, whether it is a framework error, the resolvers involved, and the error type.
         */
        data class ErrorMetadata(
            /**
             * The name of the field where the error occurred.
             */
            val fieldName: String?,
            /**
             * The type of the parent object where the error occurred.
             */
            val parentType: String?,
            /**
             * The name of the operation where the error occurred.
             */
            val operationName: String?,
            /**
             * Indicates whether the error is a framework error or caused by a tenant.
             */
            val isFrameworkError: Boolean?,
            /**
             * The list of resolvers involved in the error, represented as a string of the class name.
             *
             * Example: "MyCustomTypeResolver"
             */
            val resolvers: List<String>?,
            /**
             * The type of the error, if available.
             */
            val errorType: String?
        ) {
            fun toMap(): Map<String, String> {
                val map = mutableMapOf<String, String>()
                fieldName?.let { map["fieldName"] = it }
                parentType?.let { map["parentType"] = it }
                operationName?.let { map["operationName"] = it }
                isFrameworkError?.let { map["isFrameworkError"] = it.toString() }
                resolvers?.let { map["resolvers"] = it.joinToString(" > ") }
                errorType?.let { map["errorType"] = it }
                return map
            }

            override fun toString(): String {
                return listOfNotNull(fieldName, parentType, operationName, isFrameworkError, resolvers, errorType)
                    .joinToString(separator = ", ", prefix = "{", postfix = "}")
            }

            companion object {
                val EMPTY = ErrorMetadata(
                    fieldName = null,
                    parentType = null,
                    operationName = null,
                    isFrameworkError = null,
                    resolvers = null,
                    errorType = null
                )
            }
        }

        /**
         * A no-op implementation of [ResolverErrorReporter] that does nothing.
         */
        val NoOpResolverErrorReporter: ResolverErrorReporter = object : ResolverErrorReporter {
            override fun reportError(
                exception: Throwable,
                fieldDefinition: GraphQLFieldDefinition,
                dataFetchingEnvironment: DataFetchingEnvironment,
                errorMessage: String,
                metadata: ErrorMetadata
            ) {}
        }
    }
}
