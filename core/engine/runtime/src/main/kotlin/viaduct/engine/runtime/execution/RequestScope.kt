package viaduct.engine.runtime.execution

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletionHandlerException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import viaduct.deferred.FatalCompletionCancellationException
import viaduct.deferred.RequestParentJobContextElement
import viaduct.engine.runtime.RequestScopeCancellationException
import viaduct.utils.slf4j.logger

/** Creates coroutine scopes supervised by the request. */
internal typealias SupervisorScopeFactory = (CoroutineContext) -> CoroutineScope

internal object RequestScope {
    private val log by logger()

    /** Keeps request work alive until its owner finishes, reusing an enclosing request's scope for subqueries. */
    suspend operator fun <T> invoke(
        isolated: Boolean = false,
        block: suspend CoroutineScope.(supervisorFactory: SupervisorScopeFactory) -> T,
    ): T {
        val cc = currentCoroutineContext()
        val existingSupervisor = cc[RequestParentJobContextElement]?.requestJob?.takeIf { it.isActive }
        if (!isolated && existingSupervisor != null) {
            return executeWithSupervisor(existingSupervisor, block)
        }
        val supervisor = SupervisorJob(cc[Job])
        return try {
            executeWithSupervisor(supervisor, block)
        } finally {
            withContext(NonCancellable) {
                supervisor.cancel(RequestScopeCancellationException("Request complete. Cleaning up request scope."))
                supervisor.join()
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun <T> executeWithSupervisor(
        supervisorJob: Job,
        block: suspend CoroutineScope.(supervisorFactory: SupervisorScopeFactory) -> T,
    ): T {
        val cc = currentCoroutineContext()
        cc.ensureActive()
        val ctx = cc + supervisorJob + RequestParentJobContextElement(supervisorJob) + CoroutineExceptionHandler { _, t ->
            val failure = UnwrapExceptionUtil.unwrapException(t) { it is CompletionException || it is ExecutionException }
            if (failure !is Exception || failure is CompletionHandlerException) {
                supervisorJob.cancel(FatalCompletionCancellationException(failure))
            }
            log.error("Uncaught exception in request scope", t)
        }
        val execution = CoroutineScope(ctx).async {
            withThreadLocalCoroutineContext {
                val factory = { context: CoroutineContext -> CoroutineScope(context + supervisorJob) }
                block(factory)
            }
        }
        val cancellationHandle = cc[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) execution.cancel()
        }
        return try {
            execution.await()
        } finally {
            cancellationHandle?.dispose()
            // The execution belongs to the shared supervisor; its caller still owns its cleanup.
            withContext(NonCancellable) { execution.cancelAndJoin() }
        }
    }
}
