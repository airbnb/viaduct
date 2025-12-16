package viaduct.api.exception

import viaduct.apiannotations.StableApi

/**
 * An exception class that represents a GraphQL field error. Use this if you want to customize
 * the error entry in the GraphQL response.
 *
 * @param message The message field for the error in the GraphQL response
 * @param extensions Fields to include in a "extensions" field for the error in the GraphQL response
 *
 * TODO(https://app.asana.com/1/150975571430/task/1210755595661886?focus=true): update the engine to extract extensions into GraphQLError
 */
@StableApi
open class FieldError(
    override val message: String,
    val extensions: Map<String, Any>? = null,
    cause: Throwable? = null
) : Exception(message, cause)
