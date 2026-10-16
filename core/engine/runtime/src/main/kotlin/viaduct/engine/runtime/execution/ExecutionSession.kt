@file:Suppress("DEPRECATION")

package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.IncrementalExecutionResult.Update
import viaduct.engine.api.spi.CoroutineInterop

/**
 * Manages the execution and results of one GraphQL request.
 * It allows the caller to receive the initial response while deferred work continues.
 * Later results are saved until the caller reads them, so a slow reader does not delay execution.
 * The session cleans up the request when execution finishes or is cancelled.
 */
@Suppress("TooGenericExceptionCaught")
internal class ExecutionSession {
    /** Completes with the initial response while deferred work may still be running. */
    val initialResult = CompletableFuture<ExecutionResult>()

    /** Completes after execution and request cleanup finish. */
    val completion = CompletableFuture<Unit>()
    private val cancellation = CompletableFuture<Unit>()

    // Buffer completed updates so slow consumers do not hold up execution.
    private val updates = Channel<Update>(Channel.UNLIMITED)

    /** Starts execution using this request's result and cancellation handles. */
    fun start(
        coroutineInterop: CoroutineInterop,
        execute: suspend (SupervisorScopeFactory) -> ExecutionResult,
    ) {
        val started = AtomicBoolean()
        val producer = coroutineInterop.scopedFuture {
            started.set(true)
            var failure: Throwable? = null
            try {
                var synchronousResult: ExecutionResult? = null
                RequestScope { supervisorFactory ->
                    val result = execute(supervisorFactory)
                    if (result is IncrementalExecutionResult) {
                        forwardIncrementalResult(result)
                    } else {
                        synchronousResult = result
                    }
                }
                synchronousResult?.let { initialResult.complete(it) }
            } catch (t: Throwable) {
                failure = t
                initialResult.completeExceptionally(t)
            } finally {
                if (failure == null) completion.complete(Unit) else completion.completeExceptionally(failure)
                updates.close(failure)
            }
        }
        cancellation.thenAccept { producer.cancel(true) }
        initialResult.whenComplete { _, error ->
            if (error != null) cancellation.complete(Unit)
        }
        producer.whenComplete { _, error ->
            if (error != null) {
                initialResult.completeExceptionally(error)
                if (!started.get()) {
                    completion.completeExceptionally(error)
                    updates.close(error)
                }
            }
        }
    }

    /** Cancels execution and discards buffered updates, even before collection starts. */
    fun cancel(cause: CancellationException = CancellationException("Incremental execution cancelled")) {
        updates.cancel(cause)
        cancellation.complete(Unit)
        initialResult.completeExceptionally(cause)
    }

    /**
     * Makes the initial response available through [initialResult], then collects later updates into
     * this session's buffer. This keeps the request alive while execution continues, without waiting
     * for the caller to read the updates. The caller's result uses this buffer and cancels the whole
     * session when cancelled. This method cancels [result] when collection finishes, fails, or is
     * cancelled to release any remaining work.
     */
    private suspend fun forwardIncrementalResult(result: IncrementalExecutionResult) {
        val receivedUpdates = updates.consumeAsFlow()
        val continuation = flow {
            try {
                emitAll(receivedUpdates)
            } finally {
                cancel()
            }
        }
        try {
            // Wrap the result so callers read buffered updates and cancellation stops the whole session.
            initialResult.complete(IncrementalExecutionResultImpl(result, result.pending, result.hasNext, continuation) { cancel() })
            result.updates.collect {
                currentCoroutineContext().ensureActive()
                updates.send(it)
            }
        } finally {
            result.cancel()
        }
    }
}
