package viaduct.engine.runtime2.resolution

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

internal const val RESOLUTION_THREAD_COUNT_CONFIGURATION = "viaduct.resolution.threadcount"

/** Returns the positive externally configured resolution thread count, defaulting to one. */
fun configuredResolutionThreadCount(): Int {
    val configured: String =
        System.getProperty(RESOLUTION_THREAD_COUNT_CONFIGURATION)
            ?: System.getenv(RESOLUTION_THREAD_COUNT_CONFIGURATION)
            ?: "1"
    return configured.toIntOrNull()
        ?.takeIf { threadCount -> threadCount > 0 }
        ?: error(
            "$RESOLUTION_THREAD_COUNT_CONFIGURATION must be a positive integer: $configured",
        )
}

/**
 * Creates caller-owned fixed-thread-pool dispatchers for Resolution execution.
 *
 * Every [create] call returns a fresh dispatcher. The caller chooses the lifetime, reuses the
 * dispatcher across the resolutions in that lifetime, and closes it afterward. The factory does
 * not retain dispatchers, read configuration, or own request lifecycles.
 *
 * Worker threads are daemon threads named `resolution-N-M`: `N` is a process-local pool
 * identifier and `M` is that pool's thread identifier. Neither number is the configured thread
 * count. The names group thread-dump entries by pool and support profiler filtering and per-thread
 * CPU sampling; they are diagnostic labels and do not record observations or measurements.
 */
object ResolutionDispatcherFactory {
    private val nextPoolIdentifier = AtomicInteger()

    /**
     * Creates a fresh fixed dispatcher with [threadCount] daemon threads.
     *
     * The caller owns the returned dispatcher and must close it after reusing it for the intended
     * test, benchmark, campaign, fixture, or service lifetime.
     */
    fun create(threadCount: Int): ExecutorCoroutineDispatcher {
        require(threadCount > 0) { "threadCount must be positive: $threadCount" }
        val poolIdentifier = nextPoolIdentifier.incrementAndGet()
        val nextThreadIdentifier = AtomicInteger()
        return Executors
            .newFixedThreadPool(threadCount) { runnable ->
                Thread(
                    runnable,
                    "resolution-$poolIdentifier-${nextThreadIdentifier.incrementAndGet()}",
                ).apply {
                    isDaemon = true
                }
            }.asCoroutineDispatcher()
    }
}
