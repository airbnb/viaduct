package viaduct.engine.api.fragment.errors

import com.airbnb.viaduct.errors.ViaductException
import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.language.Field

interface IFragmentFieldResolutionError {
    val message: String
    val cause: Throwable?
    val viaductErrorType: String?
    val fatal: Boolean
}

data class FragmentFieldResolutionError(
    val fieldName: String,
    val fieldPath: ResultPath,
    val field: Field,
    override val cause: Throwable
) : IFragmentFieldResolutionError {
    override val message: String
        get() = "Error when executing field named \"$fieldName\": ${cause.message}"
    override val viaductErrorType: String?
        get() = (cause as? ViaductException)?.getErrorType()?.value
    override val fatal: Boolean
        get() = (cause as? ViaductException)?.getErrorType()?.fatal ?: true
}

data class FragmentFieldEngineResolutionError(
    val graphqlError: GraphQLError,
    override val cause: Throwable? = null
) : IFragmentFieldResolutionError {
    override val message: String
        get() = graphqlError.message
    override val viaductErrorType: String?
        get() = graphqlError.extensions?.get("errorType") as? String
    override val fatal: Boolean
        get() = graphqlError.extensions?.get("fatal") as? Boolean ?: true

    // e.g. ["nodes", "0", "name"]
    val path: List<String>
        @Suppress("UNCHECKED_CAST")
        get() = graphqlError.path as? List<String>? ?: listOf()

    // e.g. "nodes.0.name"
    val pathString: String
        get() = path.joinToString(separator = ".")
}

/**
 * Use this to get all the errors for a specific field, as represented by a partial path, e.g.
 * "viewer.user.ownedStayListings" or "ownedStayListings". This *excludes* errors on its
 * subselections, e.g., if "viewer.user.ownedStayListings.edges.0.node" has an error, it will
 * not be returned.
 */
fun List<FragmentFieldEngineResolutionError>.forField(fieldPath: String): List<FragmentFieldEngineResolutionError> {
    return filter { it.pathString.endsWith(fieldPath) }
}

/**
 * Use this to get all the errors for a specific field and its subselections
 */
fun List<FragmentFieldEngineResolutionError>.forFieldAndSubSelections(fieldPath: String): List<FragmentFieldEngineResolutionError> {
    return filter { it.pathString.contains(fieldPath) }
}
