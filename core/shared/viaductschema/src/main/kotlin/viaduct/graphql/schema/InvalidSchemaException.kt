package viaduct.graphql.schema

/**
 * Exception thrown when a schema contains invalid GraphQL schema constructs.
 *
 * This exception is thrown for semantic schema errors, for example:
 * - An applied directive references a non-existent directive definition
 * - A union member is not an Object type
 * - An Object type's super is not an Interface type
 * - An Interface's possibleObjectTypes contains a non-Object type
 *
 * This is distinct from file format errors (like invalid magic numbers or truncated data)
 * which should throw format-specific exceptions.
 */
internal class InvalidSchemaException(message: String) : Exception(message)
