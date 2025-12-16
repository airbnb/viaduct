package viaduct.service.api.spi

/**
 * Helper class for building Viaduct GraphQL errors.
 *
 * Provides a fluent API similar to graphql-java's GraphqlErrorBuilder
 * but without depending on graphql-java types.
 *
 * Example:
 * ```kotlin
 * val error = ViaductErrorBuilder.newError(errorMetadata)
 *     .message("User not found")
 *     .errorType("NOT_FOUND")
 *     .fatal(false)
 *     .build()
 * ```
 */
class ViaductErrorBuilder private constructor() {
    private var message: String = ""
    private var path: List<Any>? = null
    private var locations: List<SourceLocation>? = null
    private var errorType: String? = null
    private var fatal: Boolean? = null
    private val extensions: MutableMap<String, Any?> = mutableMapOf()

    fun message(message: String): ViaductErrorBuilder {
        this.message = message
        return this
    }

    fun path(path: List<Any>): ViaductErrorBuilder {
        this.path = path
        return this
    }

    fun location(location: SourceLocation): ViaductErrorBuilder {
        this.locations = listOf(location)
        return this
    }

    fun locations(locations: List<SourceLocation>): ViaductErrorBuilder {
        this.locations = locations
        return this
    }

    fun errorType(errorType: String): ViaductErrorBuilder {
        this.errorType = errorType
        return this
    }

    fun fatal(fatal: Boolean): ViaductErrorBuilder {
        this.fatal = fatal
        return this
    }

    fun extensions(extensions: Map<String, Any?>): ViaductErrorBuilder {
        this.extensions.putAll(extensions)
        return this
    }

    fun extension(
        key: String,
        value: Any?
    ): ViaductErrorBuilder {
        this.extensions[key] = value
        return this
    }

    fun build(): ViaductGraphQLError {
        return ViaductGraphQLError(
            message = message,
            path = path,
            locations = locations,
            errorType = errorType,
            fatal = fatal,
            extensions = if (extensions.isEmpty()) null else extensions.toMap()
        )
    }

    companion object {
        /**
         * Creates a new error builder.
         */
        fun newError(): ViaductErrorBuilder {
            return ViaductErrorBuilder()
        }

        /**
         * Creates a new error builder with context from ErrorMetadata.
         * Automatically populates path and location from the metadata.
         */
        fun newError(metadata: ErrorMetadata): ViaductErrorBuilder {
            return ViaductErrorBuilder().apply {
                metadata.executionPath?.let { path(it) }
                metadata.sourceLocation?.let { location(it) }
            }
        }
    }
}
