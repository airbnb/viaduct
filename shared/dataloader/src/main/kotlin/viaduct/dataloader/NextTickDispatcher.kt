@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package viaduct.dataloader

import java.lang.IllegalArgumentException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import viaduct.service.api.spi.FlagManager

/**
 * A custom [CoroutineDispatcher] which wraps another [CoroutineDispatcher] (most likely a built-in
 * dispatcher, such as [Dispatchers.Default] or [Dispatchers.IO]) in order to track execution of
 * coroutines within the parent context and dispatch a registered suspend block when they've all reached a
 * suspension point.
 *
 * This dispatcher is currently used to power the coroutine-native [InternalDataLoader] implementation, but could be
 * used for other use-cases in the future.
 *
 * Note: For each call stack of coroutines that the user wants to track, a new [NextTickDispatcher]
 * instance will need to be created (e.g., per request in an HTTP server or per GraphQL execution in
 * a GraphQL server). It's important to note that creating this new instance won't create a new
 * thread pool or otherwise affect the execution of the application -- managing the execution of
 * the coroutines is fully delegated to the wrapped dispatcher.
 *
 * This implementation is heavily inspired from this comment on GitHub in the GraphQL-Java project:
 * https://github.com/graphql-java/graphql-java/issues/1198#issuecomment-560082550
 *
 * Relevant blurb from the comment:
 *
 * > General idea: Dataloaders delay execution until their dispatch() method is called. The GraphQL
 * > query execution strategy executes datafetchers concurrently as it traverses the query graph.
 * > Whenever it calls a dataloader.load() method, it suspends indefinitely (gets stuck).
 * > We want to call dispatch() only when all current coroutines are suspended.
 *
 * > Create a new CoroutineDispatcher wrapping the CommonPool (the default coroutine dispatcher -
 * > or whatever dispatcher your app is using) I call it the QueueableDispatcher. It has an atomic
 * > integer counter and a mutable list of callbacks.
 * >   1a) Override the QueueableDispatcher's "execute" method to the following:
 * >     i) increment the atomic counter, then;
 * >     ii) call the "execute" method of the wrapped inner dispatcher (CommonPool), then;
 * >     iii) decrement the atomic counter, then if it's zero, execute and remove the first callback
 * >          on the list.
 * >   1b) A nextTick() method takes a callback and adds it to the aforementioned mutable list.
 * >       You can optionally return a Deferred from it, but it's not necessary for the implementation.
 *
 * @property wrappedDispatcher the underlying delegated dispatcher
 * @property nextTickQueueDispatcher a dispatcher that handles the next tick dispatch queue itself. this should be
 *                                   different from the dispatcher that we are wrapping
 * @property nextTickExceptionHandler Exception handler for the next tick queue. This is an advanced feature -- most of
 *                                    the time exception handling for nextTick callbacks should happen within the
 *                                    callback block itself.
 */
open class NextTickDispatcher(
    private val wrappedDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nextTickQueueDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nextTickExceptionHandler: CoroutineExceptionHandler? = null,
    flagManager: FlagManager = FlagManager.disabled,
) : CoroutineDispatcher() {
    /********************/

    private val nonBlockingEnqueueFlushKillSwitchEnabled = flagManager.isEnabled(FlagManager.Flags.KILLSWITCH_NON_BLOCKING_ENQUEUE_FLUSH)

    init {
        if (nextTickQueueDispatcher == wrappedDispatcher) {
            throw IllegalArgumentException(
                """
                wrappedDispatcher {$wrappedDispatcher} must be different from
                nextTickQueueDispatcher $nextTickQueueDispatcher
                """.trimIndent()
            )
        }
    }

    private val debug: NextTickDebug = NextTickDebug(this.toString())

    /**
     * A scope wrapping our queue dispatcher where the internals of the next tick dispatchers will operate
     */
    private val batchQueueScope = CoroutineScope(nextTickQueueDispatcher)

    /**
     * A thread safe counter used to track the number of current runnable blocks. When this is 0,
     * that means all current coroutines are suspended.
     */
    private val counter = AtomicInteger(0)

    /**
     * This supervisor job is the parent job of all `nextTick` callbacks. It can be used to observe (or cancel!) these
     * jobs.
     */
    internal val nextTickSupervisorJob = SupervisorJob()

    /**
     * CoroutineScope for the "nextTick" execution queue.
     */
    private val nextTickQueueScope =
        CoroutineScope(
            (wrappedDispatcher + nextTickSupervisorJob).let {
                // Add in the exception handler if it's set on the dispatcher
                if (nextTickExceptionHandler != null) {
                    it + nextTickExceptionHandler
                } else {
                    it
                }
            }
        )

    private val nextTickBatchQueue =
        NextTickBatchQueue<Pair<suspend (DispatchingContext) -> Unit, CoroutineContext>>(
            batchQueueScope = batchQueueScope
        ) { batch, nextTickDispatchingContext ->
            val jobs =
                batch.map { (nextTickBlock, nextTickCoroutineContext) ->
                    nextTickQueueScope.launch(
                        start = CoroutineStart.LAZY,
                        context = nextTickCoroutineContext.minusKey(Job) + wrappedDispatcher
                    ) {
                        // Launch the block inside supervisorScope to ensure cancellations don't propagate. Note
                        // that this scope will wait for its children to complete, so we get the behavior of
                        // executing each nextTick block in the queue sequentially.
                        supervisorScope {
                            nextTickBlock(nextTickDispatchingContext)
                        }
                    }
                }
            jobs.forEach { it.start() }
        }

    /**
     * This method is invoked when a coroutine has some code to run, and in the wrapped dispatcher,
     * is responsible for dispatching the execution of a runnable [block] onto another
     * thread in the given [context].
     *
     * When there are no further blocks to run, this looks at the queue of next tick functions and
     * runs code from there next if available.
     *
     * @param context the context in which to dispatch the runnable
     * @param block the runnable block to dispatch
     */
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable
    ) {
        counter.incrementAndGet()
        debug.onRunnableDispatch()
        val wrappedBlock = wrapBlock(block)
        wrappedDispatcher.dispatch(context, wrappedBlock)
    }

    @InternalCoroutinesApi
    override fun dispatchYield(
        context: CoroutineContext,
        block: Runnable
    ) {
        counter.incrementAndGet()
        debug.onRunnableDispatch()
        val wrappedBlock = wrapBlock(block)
        wrappedDispatcher.dispatchYield(context, wrappedBlock)
    }

    private fun wrapBlock(block: Runnable): Runnable =
        Runnable {
            // This try/finally block is just paranoia, block.run() should swallow exceptions
            try {
                block.run()
            } finally {
                if (counter.decrementAndGet() == 0) {
                    val debugInfo = debug.getNextTickMetadataAndReset()

                    if (nonBlockingEnqueueFlushKillSwitchEnabled) {
                        batchQueueScope.launch {
                            nextTickBatchQueue.flush(debugInfo)
                        }
                    } else {
                        nextTickBatchQueue.tryFlush(debugInfo)
                    }
                }
            }
        }

    /**
     * Registers a function to run on the next "tick" of this dispatchers' mimiced event loop.
     * Most of the time you'll want to use [nextTick] instead since this makes it convenient to
     * preserve the coroutine context at the call site.
     *
     * @param context the coroutine context to run this next tick in.
     * @param block the function to run on the next tick
     */
    fun nextTick(
        context: CoroutineContext,
        block: suspend (DispatchingContext) -> Unit
    ) {
        if (nonBlockingEnqueueFlushKillSwitchEnabled) {
            batchQueueScope.launch(
                start = CoroutineStart.UNDISPATCHED
            ) {
                nextTickBatchQueue.enqueue(Pair(block, context))
            }
        } else {
            nextTickBatchQueue.tryEnqueue(Pair(block, context))
        }
    }
}

/**
 * Registers a function to run on the next "tick" of the NextTickDispatcher's mimicked event loop.
 * The provided block will run within the context of the caller's coroutine scope.
 *
 * @param block the function to run on the next tick
 * @throws RuntimeException if not called within the context of a NextTickDispatcher
 */
fun nextTick(
    coroutineContext: CoroutineContext,
    block: suspend (DispatchingContext) -> Unit
) {
    val dispatcher = coroutineContext[ContinuationInterceptor]
    val nextTickDispatcher =
        dispatcher as? NextTickDispatcher
            ?: throw RuntimeException(
                "Called nextTick on a ${dispatcher?.javaClass} Dispatcher. " +
                    "Only NextTickDispatcher supports the nextTick function."
            )
    nextTickDispatcher.nextTick(coroutineContext) { dispatchingContext ->
        withContext(nextTickDispatcher) {
            block(dispatchingContext)
        }
    }
}

/**
 * Wrap an existing Dispatcher in a NextTickDispatcher
 */
fun CoroutineDispatcher.asNextTickDispatcher(flagManager: FlagManager) = NextTickDispatcher(this, flagManager = flagManager)

data class NextTickDispatchingContext(
    val tickIndex: Int,
    val metadata: NextTickMetadata? = null,
) : DispatchingContext

data class NextTickMetadata(
    val dispatcherName: String,
    val numDispatchedRunnables: Long,
    val totalTickDelayNs: Long,
)
