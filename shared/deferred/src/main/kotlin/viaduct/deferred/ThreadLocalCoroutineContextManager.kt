package viaduct.deferred

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement

/**
 * Returns the current coroutine context as stored in the [ThreadLocalCoroutineContextManager].
 * If there is no active [Job] in that context, the [Job] returned by
 * [ThreadLocalCoroutineContextManager.getCurrentDefaultJob] is added to the context before
 * returning it.
 * This ensures that any coroutines launched in this context will be properly parented.
 */
fun threadLocalCoroutineContext(): CoroutineContext {
    val threadLocalContext = ThreadLocalCoroutineContextManager.INSTANCE.getCurrentCoroutineContext()
    val currentJob = threadLocalContext[Job]
    val contextForScope =
        if (currentJob?.isActive == true) {
            threadLocalContext
        } else {
            val rootJob = ThreadLocalCoroutineContextManager.INSTANCE.getCurrentDefaultJob()
            threadLocalContext + rootJob
        }
    return contextForScope
}

/**
 * Returns the current [Job] from the [ThreadLocalCoroutineContextManager], or `null` if there
 * is no current [Job]. Avoids overhead of actually accessing the full [CoroutineContext] and
 * folding over it to find the [Job].
 */
fun threadLocalCurrentJobOrNull(): Job? {
    return ThreadLocalCoroutineContextManager.INSTANCE.getCurrentJobOrNull()
}

/**
 * [ThreadLocalCoroutineContextManager] manages a single [ThreadLocal] that, at any given time,
 * contains the currently active [coroutineContext]. This is used by [scopedFuture] (and its
 * helper functions) in order to allow launching [CompletableFuture]-based coroutines into a scope
 * that is properly parented by the [Job] that first launched the future.
 */
class ThreadLocalCoroutineContextManager {
    companion object {
        val INSTANCE = ThreadLocalCoroutineContextManager()
        private val tlCtx = ThreadLocal<CoroutineContext?>()
        private val tlJob = ThreadLocal<Job?>()
    }

    private var defaultCtx: CoroutineContext? = null

    /**
     * A [ThreadContextElement] that handles getting/setting the [ThreadLocal] managed by
     * [ThreadLocalCoroutineContextManager] as threads change during normal coroutine execution.
     */
    class ContextElement(val defaultJob: Job) :
        ThreadContextElement<Pair<CoroutineContext?, Job?>>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ContextElement>

        override fun updateThreadContext(context: CoroutineContext): Pair<CoroutineContext?, Job?> {
            val prev = tlCtx.get() to tlJob.get()
            tlCtx.set(context)
            val job = context[Job] ?: defaultJob
            tlJob.set(job)
            return prev
        }

        override fun restoreThreadContext(
            context: CoroutineContext,
            oldState: Pair<CoroutineContext?, Job?>
        ) {
            tlCtx.set(oldState.first)
            tlJob.set(oldState.second)
        }
    }

    fun setDefaultCoroutineContext(ctx: CoroutineContext?) {
        defaultCtx = ctx?.let { if (it[Job] == null) it + Job() else it }
        // also prime the fast path if caller chooses to install a default now
        if (defaultCtx != null) {
            val dc = requireNotNull(defaultCtx)
            tlCtx.set(dc)
            val ce = dc[ContextElement] as? ContextElement
            tlJob.set(dc[Job] ?: ce?.defaultJob)
        } else {
            tlCtx.set(null)
            tlJob.set(null)
        }
    }

    fun getCurrentCoroutineContext(): CoroutineContext =
        tlCtx.get() ?: defaultCtx ?: throw IllegalStateException(
            "Can't get CoroutineContext from the context manager, as no CoroutineContext is " +
                "active on this thread and no default CoroutineContext has been set."
        )

    // FAST path: O(1) read, no expensive ctx object access except in fallback case
    fun getCurrentJobOrNull(): Job? = tlJob.get() ?: (defaultCtx?.get(Job))

    fun getCurrentDefaultJob(): Job {
        val ctx = getCurrentCoroutineContext()
        val ce = ctx[ContextElement] as ContextElement
        return ce.defaultJob
    }
}
