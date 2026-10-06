package viaduct.engine.runtime2.execution

import graphql.ExecutionResult
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.incremental.IncrementalExecutionResult
import java.util.concurrent.CompletableFuture

/**
 * Connects graphql-java's final incremental result publisher to the Resolution request lifetime.
 *
 * GraphQL Java adds its incremental publisher outside the query execution strategy, so this final
 * result hook is the first boundary that can wrap publisher completion and cancellation.
 */
class QPlanInstrumentation : SimplePerformantInstrumentation() {
    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?,
    ): CompletableFuture<ExecutionResult> {
        val graphQLContext = parameters.graphQLContext
        val lifetimeKey =
            QPlanRequestLifetimeKey(
                requireNotNull(parameters.executionInput.executionId) {
                    "GraphQL execution ID must be assigned before result instrumentation"
                },
            )
        val lifetime =
            graphQLContext.get<QPlanRequestLifetime>(lifetimeKey)
                ?: return CompletableFuture.completedFuture(executionResult)
        graphQLContext.delete(lifetimeKey)
        val result =
            if (executionResult is IncrementalExecutionResult) {
                executionResult.withRequestLifetime(lifetime.requestJob)
            } else {
                lifetime.requestJob.cancel(requestCancellation("GraphQL request completed"))
                executionResult
            }
        return CompletableFuture.completedFuture(result)
    }
}
