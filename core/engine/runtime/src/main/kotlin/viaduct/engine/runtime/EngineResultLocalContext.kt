package viaduct.engine.runtime

import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategyParameters

/**
 * Local data fetcher context that contains the root [ObjectEngineResult] for the current request, the parent
 * [ObjectEngineResult] for a given field, and the query [ObjectEngineResult] for query selections.
 */
data class EngineResultLocalContext(
    val rootEngineResult: ObjectEngineResultImpl,
    val parentEngineResult: ObjectEngineResultImpl,
    val queryEngineResult: ObjectEngineResultImpl,
    val executionStrategyParams: ExecutionStrategyParameters?,
    val executionContext: ExecutionContext?
)
