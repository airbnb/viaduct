package viaduct.service.api

import java.util.UUID
import viaduct.apiannotations.StableApi

/**
 * Encapsulates the parameters necessary to execute a GraphQL operation.
 */
@StableApi
interface ExecutionInput {
    /**
     * Text of an executable document as defined in
     * the GraphQL specification.
     */
    val operationText: String

    /**
     * Name of operation in [operationText] to be executed. May be null
     * if [operationText] has only one operation.
     */
    val operationName: String?

    /** A unique ID for the operation for use in instrumentation. */
    val operationId: String

    /** Values for variables defined by the operation. */
    val variables: Map<String, Any?>

    /** Unique request identifier for this execution. */
    val executionId: String

    /**
     * Deployment-specific request context established by Viaduct Architects.
     * Will be available to application code through execution contexts.
     * We encourage the use of dependency injection over this object, but
     * support it for simpler installations.
     */
    val requestContext: Any?

    /**
     * Default implementation with proper validation.
     * Generally you should use the builder pattern instead.
     */
    private data class Default(
        override val operationText: String,
        override val operationName: String? = null,
        override val operationId: String,
        override val variables: Map<String, Any?> = emptyMap(),
        override val executionId: String,
        override val requestContext: Any? = null
    ) : ExecutionInput {
        init {
            require(operationId.isNotBlank()) { "operationId cannot be blank" }
            require(executionId.isNotBlank()) { "executionId cannot be blank" }
        }
    }

    companion object {
        /**
         * Creates a new [Builder] instance for constructing [ExecutionInput] objects.
         */
        fun builder() = Builder()

        /**
         * Convenience factory method for simple cases.
         */
        fun create(
            operationText: String,
            operationName: String? = null,
            variables: Map<String, Any?> = emptyMap(),
            requestContext: Any? = null
        ): ExecutionInput =
            builder()
                .operationText(operationText)
                .operationName(operationName)
                .variables(variables)
                .requestContext(requestContext)
                .build()

        private fun generateOperationId(
            operationText: String,
            operationName: String?
        ): String {
            val hash = operationText.hashCode().toString(16)
            return if (operationName != null) "$hash-$operationName" else hash
        }

        private fun generateExecutionId(): String = UUID.randomUUID().toString()
    }

    /**
     * Builder class for creating [ExecutionInput] instances with proper defaults
     * and validation.
     */
    class Builder {
        private var operationText: String? = null
        private var operationName: String? = null
        private var operationId: String? = null
        private var variables: Map<String, Any?> = emptyMap()
        private var executionId: String? = null
        private var requestContext: Any? = null

        fun operationText(operationText: String) = apply { this.operationText = operationText }

        fun operationName(operationName: String?) = apply { this.operationName = operationName }

        fun operationId(operationId: String) = apply { this.operationId = operationId }

        fun variables(variables: Map<String, Any?>) = apply { this.variables = variables }

        fun executionId(executionId: String) = apply { this.executionId = executionId }

        fun requestContext(requestContext: Any?) = apply { this.requestContext = requestContext }

        fun build(): ExecutionInput {
            val finalOperationText = checkNotNull(operationText) { "operationText is required" }
            val finalOperationId = operationId ?: generateOperationId(finalOperationText, operationName)
            val finalExecutionId = executionId ?: generateExecutionId()

            return Default(
                operationText = finalOperationText,
                operationName = operationName,
                operationId = finalOperationId,
                variables = variables,
                executionId = finalExecutionId,
                requestContext = requestContext
            )
        }
    }
}
