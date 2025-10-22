package viaduct.engine.api

import java.util.UUID

/**
 * Input required to execute a GraphQL operation in the engine.
 *
 * @param operationText The raw text of the GraphQL operation.
 * @param operationName The name of the operation to execute (if multiple operations are present).
 * @param operationId A unique identifier for the operation (e.g., a hash of the operation text).
 * @param variables A map of variable names to their values for the operation.
 * @param executionId A unique identifier for this execution instance (e.g., a UUID).
 * @param requestContext An optional context object that can hold additional information about the request (e.g., authentication info, request metadata).
 */
data class ExecutionInput(
    val operationText: String,
    val operationName: String? = null,
    val operationId: String = run {
        val hash = operationText.hashCode().toString(16)
        if (operationName != null) "$hash-$operationName" else hash
    },
    val variables: Map<String, Any?> = emptyMap(),
    val executionId: String = UUID.randomUUID().toString(),
    val requestContext: Any?
)
