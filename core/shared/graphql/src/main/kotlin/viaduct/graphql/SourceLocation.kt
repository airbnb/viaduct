package viaduct.graphql

/**
 * Represents a location in a GraphQL query document.
 *
 * @property line The line number in the source document (1-indexed).
 * @property column The column number in the source document (1-indexed).
 * @property sourceName Optional name of the source document.
 */
data class SourceLocation(
    val line: Int,
    val column: Int,
    val sourceName: String? = null
)
