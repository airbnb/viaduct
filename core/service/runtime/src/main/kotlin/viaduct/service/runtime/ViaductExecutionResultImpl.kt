package viaduct.service.runtime

import graphql.ExecutionResult as GJExecutionResult
import graphql.GraphQLError as GJGraphQLError
import viaduct.graphql.SourceLocation
import viaduct.service.api.ExecutionResult
import viaduct.service.api.GraphQLError

/**
 * Converts a GraphQL Java [GJGraphQLError] to a Viaduct [GraphQLError].
 */
private fun GJGraphQLError.toViaductError(): GraphQLError =
    GraphQLError(
        message = message ?: "",
        path = path,
        locations = locations?.map { location ->
            SourceLocation(
                line = location.line,
                column = location.column,
                sourceName = location.sourceName
            )
        },
        extensions = extensions ?: emptyMap()
    )

/**
 * Internal implementation of [ExecutionResult] that wraps a GraphQL Java [GJExecutionResult].
 */
internal class ExecutionResultImpl(
    private val executionResult: GJExecutionResult
) : ExecutionResult {
    @Suppress("UNCHECKED_CAST")
    override fun getData(): Map<String, Any?>? {
        val data = executionResult.getData<Any?>()
        return when (data) {
            null -> null
            is Map<*, *> -> data as Map<String, Any?>
            else -> mapOf("data" to data)
        }
    }

    override val errors: List<GraphQLError>
        get() = executionResult.errors.map { it.toViaductError() }

    override val extensions: Map<Any, Any?>?
        get() = executionResult.extensions

    override fun toSpecification(): Map<String, Any?> = executionResult.toSpecification()
}

/**
 * Extension function to convert a GraphQL Java [GJExecutionResult] to an [ExecutionResult].
 */
internal fun GJExecutionResult.toExecutionResult(): ExecutionResult = ExecutionResultImpl(this)
