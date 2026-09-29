package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import semantics.shared.SharedTaskDispatcher

/** Owns the permitted request-root coroutine kinds for Resolver21-23 and Resolver26. */
internal class CoroutineTaskDispatcher(
    private val requestScope: CoroutineScope,
) : SharedTaskDispatcher<CoroutineOrchestrationTaskBase<*>, CoroutinePublicationOccurrence> {
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

    fun dispatchFieldChecker(publication: CoroutinePublicationOccurrence) = publication.dispatch(requestScope)

    fun dispatchTypeChecker(publication: CoroutinePublicationOccurrence) = publication.dispatch(requestScope)
}
