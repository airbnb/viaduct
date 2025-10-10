package viaduct.engine

/**
 * Exception thrown when there is an error loading a Viaduct schema.
 *
 * @param message The error message.
 * @param cause The underlying cause of the error, if any.
 */
class ViaductSchemaLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)
