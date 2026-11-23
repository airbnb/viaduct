@file:Suppress("DEPRECATION") // CoroutineInterop retained for Airbnb

package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategy
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.future.await
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.context.DispatcherLocalContext
import viaduct.engine.runtime.context.getLocalContextForType

/**
 * WrappedCoroutineExecutionStrategy executes the passed-in executionStrategy within the scope of a coroutine. This
 * ensures that the CompletableFuture chain within the executionStrategy is executed within the context of the provided
 * CoroutineScope. It also ensures that the dispatcher used by the executionStrategy is the dispatcher provided in the
 * execution input's local context.
 *
 * @property executionStrategy the execution strategy to wrap within a CoroutineScope
 */
class WrappedCoroutineExecutionStrategy(
    private val executionStrategy: ExecutionStrategy,
    private val coroutineInterop: CoroutineInterop,
    dataFetcherExceptionHandler: DataFetcherExceptionHandler = SimpleDataFetcherExceptionHandler()
) : ExecutionStrategy(dataFetcherExceptionHandler) {
    override fun execute(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters
    ): CompletableFuture<ExecutionResult> {
        if (parameters.parent == null) {
            // Reuse ViaductExecutionStrategy's session to avoid calling incremental instrumentation twice.
            if (executionStrategy is ViaductExecutionStrategy) return executionStrategy.execute(executionContext, parameters)
            return ExecutionSession {
                val engineExecutionContext = executionContext.executionInput.getLocalContextForType<EngineExecutionContextImpl>()
                if (engineExecutionContext?.incrementalExecutionEnabled == true) {
                    (executionContext.instrumentation as? ViaductModernGJInstrumentation)?.beginIncrementalExecution(
                        InstrumentationExecutionParameters(executionContext.executionInput, executionContext.graphQLSchema),
                        executionContext.instrumentationState
                    )
                } else {
                    null
                }
            }.also { session ->
                val dispatcher = executionContext.executionInput.getLocalContextForType<DispatcherLocalContext>()?.dispatcher
                session.start(coroutineInterop, dispatcher ?: EmptyCoroutineContext) {
                    executionStrategy.execute(executionContext, parameters).await()
                }
            }.initialResult
        }
        return executionStrategy.execute(executionContext, parameters)
    }

    override fun resolveFieldWithInfo(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters
    ): Any =
        if (executionStrategy is AdaptedExecutionStrategy) {
            executionStrategy.resolveFieldWithInfo(executionContext, parameters)
        } else {
            super.resolveFieldWithInfo(executionContext, parameters)
        }
}
