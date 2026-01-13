package viaduct.service.api.spi

import viaduct.graphql.SourceLocation
import viaduct.service.api.GraphQLError

/**
 * Helper class for building GraphQL errors.
 */
class ErrorBuilder private constructor() {
    private var message: String = ""
    private var path: List<Any>? = null
    private var locations: List<SourceLocation>? = null
    private val extensions: MutableMap<String, Any?> = mutableMapOf()

    fun message(message: String): ErrorBuilder {
        this.message = message
        return this
    }

    fun path(path: List<Any>): ErrorBuilder {
        this.path = path
        return this
    }

    fun location(location: SourceLocation): ErrorBuilder {
        this.locations = listOf(location)
        return this
    }

    fun locations(locations: List<SourceLocation>): ErrorBuilder {
        this.locations = locations
        return this
    }

    fun extensions(extensions: Map<String, Any?>): ErrorBuilder {
        this.extensions.putAll(extensions)
        return this
    }

    fun extension(
        key: String,
        value: Any?
    ): ErrorBuilder {
        this.extensions[key] = value
        return this
    }

    fun build(): GraphQLError =
        GraphQLError(
            message = message,
            path = path,
            locations = locations,
            extensions = if (extensions.isEmpty()) null else extensions.toMap()
        )

    companion object {
        /**
         * Creates a new error builder.
         */
        fun newError(): ErrorBuilder = ErrorBuilder()

        /**
         * Creates a new error builder with context from ErrorReporter.Metadata.
         * Automatically populates path and location from the metadata.
         */
        fun newError(metadata: ErrorReporter.Metadata): ErrorBuilder =
            ErrorBuilder().apply {
                metadata.executionPath?.let { path(it) }
                metadata.sourceLocation?.let { location(it) }
            }
    }
}
