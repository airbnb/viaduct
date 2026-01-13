package viaduct.service.api

import viaduct.graphql.SourceLocation

/**
 * Viaduct's representation of a GraphQL error.
 *
 * This type is provided by Viaduct to service engineers for building custom error responses.
 *
 * @property message The error message to display to clients.
 * @property path The execution path where the error occurred (e.g., ["user", "profile", "name"]).
 * @property locations Source locations in the GraphQL query where the field was requested.
 * @property extensions Additional error metadata (e.g., "localizedMessage", custom fields).
 */
data class GraphQLError(
    val message: String,
    val path: List<Any>? = null,
    val locations: List<SourceLocation>? = null,
    val extensions: Map<String, Any?>? = null
)
