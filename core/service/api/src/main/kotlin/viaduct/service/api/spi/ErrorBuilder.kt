package viaduct.service.api.spi

import viaduct.apiannotations.StableApi
import viaduct.graphql.SourceLocation
import viaduct.service.api.GraphQLError

/**
 * Builder for constructing [GraphQLError] instances.
 *
 * Typically used inside a [ResolverErrorBuilder] implementation to build custom error
 * responses. When created via [newError(metadata)][newError], the path and location are
 * automatically populated from the [ErrorReporter.Metadata].
 *
 * @see ResolverErrorBuilder
 */
@StableApi
class ErrorBuilder private constructor() {
    private var message: String = ""
    private var path: List<Any>? = null
    private var locations: List<SourceLocation>? = null
    private val extensions: MutableMap<String, Any?> = mutableMapOf()

    /** Sets the human-readable error message. */
    fun message(message: String): ErrorBuilder {
        this.message = message
        return this
    }

    /** Sets the execution path to the field that produced this error. */
    fun path(path: List<Any>): ErrorBuilder {
        this.path = path
        return this
    }

    /** Sets a single source location for this error. */
    fun location(location: SourceLocation): ErrorBuilder {
        this.locations = listOf(location)
        return this
    }

    /** Sets multiple source locations for this error. */
    fun locations(locations: List<SourceLocation>): ErrorBuilder {
        this.locations = locations
        return this
    }

    /** Merges the given entries into this error's extensions map. */
    fun extensions(extensions: Map<String, Any?>): ErrorBuilder {
        this.extensions.putAll(extensions)
        return this
    }

    /** Adds a single key-value pair to this error's extensions map. */
    fun extension(
        key: String,
        value: Any?
    ): ErrorBuilder {
        this.extensions[key] = value
        return this
    }

    /** Builds and returns the [GraphQLError]. */
    fun build(): GraphQLError =
        GraphQLError(
            message = message,
            path = path,
            locations = locations,
            extensions = extensions.toMap()
        )

    @StableApi
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
