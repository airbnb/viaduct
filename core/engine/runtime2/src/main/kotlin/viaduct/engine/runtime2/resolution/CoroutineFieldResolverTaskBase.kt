package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.apiannotations.InternalApi
import viaduct.engine.runtime2.resolution.framework.SharedFieldPublicationOccurrence
import viaduct.engine.runtime2.resolution.framework.SharedFieldResolverTask
import viaduct.errors.isJvmSignal

/**
 * Owns one field's helper scope and its exception-to-publication boundary.
 * Supplies its concretely typed publication through [publication], separately from the task lifecycle.
 */
@InternalApi
abstract class CoroutineFieldResolverTaskBase<P : SharedFieldPublicationOccurrence<*, *>>(
    final override val publication: P,
) : SharedFieldResolverTask<P> {
    /** Available after entry; owns this field's helper coroutines. */
    lateinit var fieldTaskScope: CoroutineScope
        private set

    /** Publishes ordinary failures while preserving actual cancellation, interruption and JVM Errors. */
    suspend fun run(scope: CoroutineScope) {
        fieldTaskScope = scope
        try {
            executeAndPublish()
        } catch (cause: Exception) {
            if (cause.isJvmSignal()) throw cause
            currentCoroutineContext().ensureActive()
            publishFailure(cause)
        }
    }

    protected abstract suspend fun executeAndPublish()

    protected abstract fun publishFailure(cause: Exception)
}
