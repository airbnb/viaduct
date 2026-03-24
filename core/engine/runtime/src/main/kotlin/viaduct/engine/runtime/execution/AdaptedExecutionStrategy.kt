package viaduct.engine.runtime.execution

import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategyParameters

/**
 * AdaptedExecutionStrategy allows public access to some otherwise inaccessible members of ExecutionStrategy
 *
 * It is useful for composing ExecutionStrategy objects, where an outer ExecutionStrategy may delegate to
 * an inner ExecutionStrategy. In this case, requiring that the inner strategy is an AdaptedExecutionStrategy
 * ensures that the delegated methods can be invoked.
 */
interface AdaptedExecutionStrategy {
    fun resolveFieldWithInfo(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters
    ): Any
}
