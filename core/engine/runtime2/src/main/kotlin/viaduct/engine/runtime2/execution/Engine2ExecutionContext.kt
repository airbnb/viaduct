package viaduct.engine.runtime2.execution

import graphql.execution.ExecutionContext
import viaduct.engine.api.EngineExecutionContext

/** GraphQL metadata for the current invocation, including independent fragment executions. */
interface Engine2ExecutionContext : EngineExecutionContext {
    val graphQLExecutionContext: ExecutionContext
}
