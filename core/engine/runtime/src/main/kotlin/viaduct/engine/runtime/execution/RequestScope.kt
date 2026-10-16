package viaduct.engine.runtime.execution

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import viaduct.deferred.RequestParentJobContextElement
import viaduct.engine.runtime.RequestScopeCancellationException
import viaduct.utils.slf4j.logger

/** Creates coroutine scopes supervised by the request. */
internal typealias SupervisorScopeFactory = (CoroutineContext) -> CoroutineScope

internal object RequestScope {
    private val log by logger()

    /** Keeps request work alive until its owner finishes, reusing an enclosing request's scope for subqueries. */
    suspend operator fun <T> invoke(block: suspend CoroutineScope.(supervisorFactory: SupervisorScopeFactory) -> T): T {
        val cc = currentCoroutineContext()
        val existingSupervisor = cc[RequestParentJobContextElement]?.requestJob?.takeIf { it.isActive }
        if (existingSupervisor != null) {
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

    private suspend fun <T> executeWithSupervisor(
        supervisorJob: Job,
        block: suspend CoroutineScope.(supervisorFactory: SupervisorScopeFactory) -> T,
    ): T {
        val ctx = currentCoroutineContext() + RequestParentJobContextElement(supervisorJob) + CoroutineExceptionHandler { _, t ->
            log.error("Uncaught exception in request scope", t)
        }
        return CoroutineScope(ctx).async {
            withThreadLocalCoroutineContext {
                val factory = { context: CoroutineContext -> CoroutineScope(context + supervisorJob) }
                block(factory)
            }
        }.await()
    }
}
