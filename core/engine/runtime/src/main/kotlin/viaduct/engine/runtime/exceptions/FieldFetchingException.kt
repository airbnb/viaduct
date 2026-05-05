package viaduct.engine.runtime.exceptions

import graphql.ErrorClassification
import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import viaduct.errors.PassthroughException

/**
 * Wraps exceptions that originate from field fetchers
 * @see viaduct.engine.runtime.execution.InternalEngineException
 */
class FieldFetchingException private constructor(
    val path: ResultPath,
    val location: SourceLocation,
    override val cause: Throwable? = null
) : RuntimeException(cause?.message, cause), PassthroughException {
    init {
        require(cause !is FieldFetchingException || this.path != cause.path) {
            "FieldFetchingException should not be recursively applied for the same field"
        }
    }

    fun toGraphQLError(): GraphQLError {
        return GraphQLError.newError()
            .message(cause?.message ?: "Unknown error")
            .errorType(ErrorClassification.errorClassification("VIADUCT_FIELD_FETCHING_EXCEPTION"))
            .path(path)
            .location(location)
            .build()
    }

    companion object {
        fun wrapWithPathAndLocation(
            cause: Throwable,
            path: ResultPath,
            location: SourceLocation
        ) = FieldFetchingException(path, location, cause)
    }
}
