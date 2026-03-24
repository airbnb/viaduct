package viaduct.graphql.schema.binary

/**
 * Exception thrown when decoding a binary schema file that has an invalid or corrupted format.
 *
 * This exception is thrown for structural problems with the binary file, such as:
 * - Invalid magic numbers
 * - Unsupported file version
 * - Truncated or corrupted data
 * - Invalid section markers
 * - Out-of-bounds references
 *
 * This is distinct from [InvalidSchemaException] which indicates the file format is valid
 * but the schema content violates GraphQL semantics.
 */
class InvalidFileFormatException(message: String) : Exception(message)
