package viaduct.engine.api

class GraphQLBuildError(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
