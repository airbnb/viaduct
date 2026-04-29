package viaduct.engine.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet

/**
 * An EngineObjectData that is not fully resolved until [resolveData] is called.
 */
interface LazyEngineObjectData : EngineObjectData {
    /**
     * Resolves the data for this lazy object.
     *
     * @return the resolved [EngineObjectData]
     */
    suspend fun resolveData(
        selections: EngineSelectionSet,
        context: EngineExecutionContext,
    ): EngineObjectData
}

/**
 * Helper for lazy data implementations that ensures a resolution block runs at most once.
 * Subsequent calls await the first resolution and return the resolved [EngineObjectData]
 * (or re-throw the exception thrown by that first resolution).
 */
internal class ResolveOnce {
    private val deferred = CompletableDeferred<EngineObjectData>()
    private val called = AtomicBoolean(false)

    /** Suspends until the first [resolve] call completes and returns its result. */
    suspend fun await(): EngineObjectData = deferred.await()

    /**
     * Runs [block] exactly once. Subsequent calls await the first resolution and return
     * the same result (or re-throw the original exception).
     *
     * @return the [EngineObjectData] produced by [block]
     * @throws Exception if [block] threw
     */
    suspend fun resolve(block: suspend () -> EngineObjectData): EngineObjectData {
        if (!called.compareAndSet(false, true)) {
            return deferred.await()
        }
        try {
            val result = block()
            deferred.complete(result)
            return result
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            deferred.completeExceptionally(e)
            throw e
        }
    }
}
