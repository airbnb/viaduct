package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import semantics.shared.SharedFieldPublicationOccurrence
import semantics.shared.SharedFieldResolverTask

/**
 * Owns one field's helper scope and its exception-to-publication boundary.
 * Supplies its concretely typed publication through [publication], separately from the task lifecycle.
 */
internal abstract class CoroutineFieldResolverTaskBase<P : SharedFieldPublicationOccurrence<*, *>>(
    final override val publication: P,
    /** Structured child scope of the dispatched field task, not the request root. */
    val fieldTaskScope: CoroutineScope,
) : SharedFieldResolverTask<P> {
    /** Publishes ordinary failures while preserving actual cancellation and JVM Errors. */
    suspend fun run() {
        try {
            executeAndPublish()
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            publishFailure(cause)
        }
    }

    protected abstract suspend fun executeAndPublish()

    protected abstract fun publishFailure(cause: Exception)
}
