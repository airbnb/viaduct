package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.SharedTaskDispatcher
import viaduct.engine.runtime2.resolution.framework.awaitMutationPayloadCompletion

/** Owns the permitted request-root coroutine kinds for Resolver21-23 and Resolution. */
internal class CoroutineTaskDispatcher(
    private val requestScope: CoroutineScope,
) : SharedTaskDispatcher<CoroutineOrchestrationTaskBase<*>, CoroutinePublicationOccurrence, CoroutinePublicationOccurrence> {
    override fun dispatchOrchestration(task: CoroutineOrchestrationTaskBase<*>) {
        task.checkDispatch()
        if (task.hasActiveWork) {
            // Installation remains synchronous even when the coroutine dispatcher queues execution.
            requestScope.launch(start = CoroutineStart.UNDISPATCHED) { task.run() }
        } else {
            task.run()
        }
    }

    override fun dispatchFieldResolver(publication: CoroutinePublicationOccurrence) = publication.dispatch(requestScope)

    /** Dispatches an ordinary mutation field task and finishes its task and payload work. */
    override suspend fun dispatchMutationField(
        publication: CoroutinePublicationOccurrence,
        cell: EngineResultCell
    ) {
        coroutineScope { publication.dispatch(this) }
        cell.awaitMutationPayloadCompletion()
    }

    fun dispatchMutationOrchestration(task: MutationOrchestrationTask<*>) {
        task.checkDispatch()
        requestScope.launch { task.run() }.invokeOnCompletion { cause ->
            if (cause is CancellationException) task.cancel(cause)
        }
    }

    fun dispatchFieldChecker(publication: CoroutinePublicationOccurrence) = publication.dispatch(requestScope)

    fun dispatchTypeChecker(publication: CoroutinePublicationOccurrence) = publication.dispatch(requestScope)
}
