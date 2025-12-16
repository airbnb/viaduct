package viaduct.service.api.spi

/**
 * Represents a location in a GraphQL query document.
 * Viaduct-owned type to avoid depending on graphql-java's SourceLocation.
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
