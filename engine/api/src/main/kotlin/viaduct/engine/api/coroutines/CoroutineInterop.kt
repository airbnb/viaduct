package viaduct.engine.api.coroutines

import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred

/**
 * Bridges non-suspending to suspending contexts, which is useful when transitioning from java-world to kotlin-world.
 * We will refactor our coroutine/threadlocal interop classes to be unified between Airbnb and OSS installations, at which point this will be deleted.
 * This interface lets the Airbnb installations use the Airbnb-coupled implementation of `scopedFuture`,
 * while the default implementation is a small fork that takes out Airbnb-specific logic (while it lasts).
 */
interface CoroutineInterop {
    /**
     * Enters a threadlocal coroutine context for a top-level request.
     */

    fun <T> enterThreadLocalCoroutineContext(
        callerContext: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> T,
    ): CompletableFuture<T>

    /**
     * Bridges non-suspending to suspending contexts using the threadlocal coroutine context.
     */
    fun <T> scopedFuture(block: suspend CoroutineScope.() -> T): CompletableFuture<T>

    /**
     * Starts a new coroutine within the current [CoroutineScope] and returns its result as a [Deferred].
     */
    fun <T> scopedAsync(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T>
}
