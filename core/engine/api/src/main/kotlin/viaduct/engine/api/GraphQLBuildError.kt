package viaduct.engine.api

/**
 * Signals a fatal error encountered while constructing or validating the GraphQL schema.
 *
 * Thrown during schema-build time (parse errors, type conflicts, invalid wiring, etc.) as
 * opposed to errors that occur at query-execution time. Catching this exception indicates
 * that the schema itself is malformed and the server cannot start.
 */
class GraphQLBuildError(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
