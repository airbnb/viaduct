package viaduct.service.api.spi

/**
 * Viaduct's representation of a GraphQL error.
 *
 * @property message The error message to display to clients.
 * @property path The execution path where the error occurred (e.g., ["user", "profile", "name"]).
 * @property locations Source locations in the GraphQL query where the field was requested.
 * @property errorType The type of error. Optional for OSS flexibility.
 * @property fatal Whether this error should be treated as fatal. Optional for OSS flexibility.
 * @property extensions Additional error metadata (e.g., "localizedMessage", custom fields).
 */
data class ViaductGraphQLError(
    val message: String,
    val path: List<Any>? = null,
    val locations: List<SourceLocation>? = null,
    val errorType: String? = null,
    val fatal: Boolean? = null,
    val extensions: Map<String, Any?>? = null
)
