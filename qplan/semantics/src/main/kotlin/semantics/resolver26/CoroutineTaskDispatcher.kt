package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import model.EngineResultCell
import semantics.shared.SharedFieldPublicationOccurrence
import semantics.shared.SharedTaskDispatcher

/** Coroutine-family publication capability needed for checker cancellation before task entry. */
internal interface CoroutineFieldCheckerPublicationOccurrence {
    val publicationCell: EngineResultCell
}

/** Owns the permitted request-root coroutine kinds for Resolver21-23 and Resolver26. */
internal class CoroutineTaskDispatcher<
    O : CoroutineOrchestrationTaskBase<*>,
    F : SharedFieldPublicationOccurrence<*, *>,
    C : CoroutineFieldCheckerPublicationOccurrence,
>(
    private val requestScope: CoroutineScope,
    private val runFieldResolver: suspend (F, CoroutineScope) -> Unit,
    private val cancelFieldResolver: (F, CancellationException) -> Unit = { publication, cause ->
        publication.publicationCell.cancelValue(cause)
    },
    private val runFieldChecker: suspend (C, CoroutineScope) -> Unit = { _, _ ->
        throw UnsupportedOperationException("This coroutine dispatcher does not support field checkers")
    },
    private val cancelFieldChecker: (C, CancellationException) -> Unit = { publication, cause ->
        publication.publicationCell.cancelFieldCheckerResult(cause)
    },
) : SharedTaskDispatcher<O, F> {
    override fun dispatchOrchestration(task: O) {
        task.checkDispatch()
        if (task.hasActiveWork) {
            // Installation remains synchronous even when the coroutine dispatcher queues execution.
            requestScope.launch(start = CoroutineStart.UNDISPATCHED) { task.run() }
        } else {
            task.run()
        }
    }

    override fun dispatchFieldResolver(publication: F) {
        requestScope.launch {
            runFieldResolver(publication, this)
        }.invokeOnCompletion { cause ->
            // Also terminates owned promises when cancellation prevents task entry.
            if (cause is CancellationException) cancelFieldResolver(publication, cause)
        }
    }

    fun dispatchFieldChecker(publication: C) {
        requestScope.launch {
            runFieldChecker(publication, this)
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) cancelFieldChecker(publication, cause)
        }
    }
}
