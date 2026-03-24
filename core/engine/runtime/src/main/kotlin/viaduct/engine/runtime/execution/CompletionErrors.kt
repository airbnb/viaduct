package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.SerializationError
import graphql.execution.NonNullableFieldWasNullException
import graphql.schema.CoercingSerializeException

internal object CompletionErrors {
    /**
     * Only used within the execution strategy to enable non-null bubbling when a field has an error.
     *
     * @property nonNullException The non-null exception that was thrown.
     * @property underlyingException The underlying exception that caused the field to be null.
     */
    class NonNullableFieldWithErrorException(
        val nonNullException: NonNullableFieldWasNullException,
        val underlyingException: Throwable
    ) : Exception(underlyingException.message, underlyingException)

    class FieldCompletionException(
        val graphQLErrors: List<GraphQLError>,
        override val cause: Throwable? = null
    ) : RuntimeException(cause) {
        constructor(cause: Throwable, parameters: ExecutionParameters) : this(
            when (cause) {
                is InternalEngineException -> listOf(cause.toGraphQLError())
                is CoercingSerializeException -> listOf(SerializationError(parameters.path, cause))
                else -> listOf(
                    InternalEngineException.wrapWithPathAndLocation(cause, parameters.path, parameters.field!!.sourceLocation).toGraphQLError()
                )
            },
            cause
        )
        constructor(cause: Throwable, graphQLErrors: List<GraphQLError>) : this(graphQLErrors, cause)
    }
}
