package viaduct.engine.runtime.execution

import graphql.ErrorClassification
import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.language.SourceLocation

/**
 * Wraps exceptions that do not originate from field fetchers.
 * This is a broad category and can include:
 *   - viaduct framework execution exceptions
 *   - exceptions that originate from instrumentation hooks
 *   - exceptions that originate from completion, such as coercing errors
 *
 * @see viaduct.engine.runtime.exceptions.FieldFetchingException
 */
class InternalEngineException private constructor(
    val path: ResultPath,
    val location: SourceLocation,
    override val cause: Throwable? = null
) : RuntimeException(cause?.message, cause) {
    init {
        require(cause !is InternalEngineException) {
            "InternalEngineException should not be recursively applied"
        }
    }

    fun toGraphQLError(): GraphQLError {
        return GraphQLError.newError()
            .message(cause?.message)
            .errorType(ErrorClassification.errorClassification("VIADUCT_INTERNAL_ENGINE_EXCEPTION"))
            .path(path)
            .location(location)
            .build()
    }

    companion object {
        fun wrapWithPathAndLocation(
            cause: Throwable,
            path: ResultPath,
            location: SourceLocation
        ) = InternalEngineException(path, location, cause)
    }
}
