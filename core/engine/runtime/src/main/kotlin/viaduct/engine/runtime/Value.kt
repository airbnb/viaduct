package viaduct.engine.runtime

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.deferred.CompletionCaller
import viaduct.deferred.cancelledDeferred
import viaduct.deferred.completedDeferred
import viaduct.deferred.currentRequestParentJobOrNull
import viaduct.deferred.exceptionalDeferred
import viaduct.deferred.exceptionallyCompose
import viaduct.deferred.handle
import viaduct.deferred.maybeRethrowCompletionHandlerException
import viaduct.deferred.propagateLocalFailure
import viaduct.deferred.propagateUpstreamFailure
import viaduct.deferred.thenApply
import viaduct.deferred.thenCompose
import viaduct.deferred.waitAllDeferreds

/**
 * Value<T> provides a uniform interface for transforming asynchronous,
 * synchronous, or exceptional values.
 *
 * Methods that transform a Value will bias towards returning a synchronous result wherever possible.
 */

sealed interface Value<T> {
    /**
     * Transform the current value to compute a new [Value]. If the current value is exceptional,
     * the result will also be exceptional with the same exception, and [block] will not be called.
     *
     * Any exceptions thrown by [block] will not be caught and will be thrown to the caller
     * of this function.
     */
    fun <U> map(block: (T) -> U): Value<U>

    /**
     * Transform the current value to compute a new [Value]. If the current value is exceptional,
     * the result will also be exceptional with the same exception, and [block] will not be called.
     *
     * Any exceptions thrown by [block] will not be caught and will be thrown to the caller
     * of this function.
     */
    fun <U> flatMap(block: (T) -> Value<U>): Value<U>

    /**
     * Return a [Deferred] describing the current value.
     * The returned Deferred may be in an already-completed or pending state.
     */
    fun asDeferred(): Deferred<T>

    /**
     * Returns the provided value, or throws if this Value is in an exceptional state
     */
    suspend fun await(): T

    /**
     * Awaits the value and returns it, or invokes [block] with the exception and returns its result.
     * Does not throw for errors in this Value — they are passed to [block]. Exceptions thrown by
     * [block] itself will propagate to the caller.
     */
    suspend fun awaitOrElse(block: (Throwable) -> @UnsafeVariance T): T

    /**
     * Return the completed value, or throw if this Value is not yet completed or is in an exceptional state.
     */
    fun getCompleted(): T

    /**
     * Recover an exceptional [Value] using the provided [block]
     *
     * Any exceptions thrown by [block] will not be caught and will be thrown to the caller
     * of this function.
     */
    fun recover(block: (Throwable) -> Value<T>): Value<T>

    /**
     * Transform the current value (if non-exceptional) and throwable (if exceptional) to compute a
     * new [Value].
     *
     * Any exceptions thrown by [block] will not be caught and will be thrown to the caller
     * of this function.
     */
    fun <U> thenApply(block: (T?, Throwable?) -> U): Value<U>

    /** Runs [block] on completion; use instead of [thenApply] when its result is not needed. */
    fun onCompletion(block: (T?, Throwable?) -> Unit)

    /**
     * Transform the current value (if non-exceptional) and throwable (if exceptional) to compute a
     * new [Value].
     *
     * Any exceptions thrown by [block] will not be caught and will be thrown to the caller
     * of this function.
     */
    fun <U> thenCompose(block: (T?, Throwable?) -> Value<U>): Value<U>

    /** A subspecies of [Value], for synchronous values */
    sealed interface Sync<T> : Value<T> {
        /** return the provided value, or throw if this Value is in an exceptional state */
        fun getOrThrow(): T

        override suspend fun await() = getOrThrow()
    }

    /** A representation of a synchronous non-exceptional [Value], backed by the provided value */
    @JvmInline
    private value class SyncValue<T>(val value: T) : Sync<T> {
        override fun getOrThrow(): T = value

        override fun getCompleted(): T = value

        override fun <U> map(block: (T) -> U): Value<U> = SyncValue(block(value))

        override fun <U> flatMap(block: (T) -> Value<U>): Value<U> = block(value)

        override fun asDeferred(): Deferred<T> = completedDeferred(value)

        override fun recover(block: (Throwable) -> Value<T>): Value<T> = this

        override suspend fun awaitOrElse(block: (Throwable) -> @UnsafeVariance T): T = value

        override fun <U> thenApply(block: (T?, Throwable?) -> U): Value<U> = SyncValue(block(value, null))

        override fun onCompletion(block: (T?, Throwable?) -> Unit) = block(value, null)

        override fun <U> thenCompose(block: (T?, Throwable?) -> Value<U>): Value<U> = block(value, null)
    }

    /** A representation of an exceptional [Value], backed by a Throwable */
    @JvmInline
    @Suppress("UNCHECKED_CAST")
    private value class SyncThrow<T>(val throwable: Throwable) : Sync<T> {
        override fun <U> map(block: (T) -> U): Value<U> = this as Value<U>

        override fun <U> flatMap(block: (T) -> Value<U>): Value<U> = this as Value<U>

        override fun getCompleted(): T = getOrThrow()

        override fun asDeferred(): Deferred<T> = exceptionalDeferred(throwable)

        override fun recover(block: (Throwable) -> Value<T>): Value<T> = block(throwable)

        override suspend fun awaitOrElse(block: (Throwable) -> @UnsafeVariance T): T = block(throwable)

        override fun <U> thenApply(block: (T?, Throwable?) -> U): Value<U> = SyncValue(block(null, throwable))

        override fun onCompletion(block: (T?, Throwable?) -> Unit) = block(null, throwable)

        override fun <U> thenCompose(block: (T?, Throwable?) -> Value<U>): Value<U> = block(null, throwable)

        override fun getOrThrow(): T = throw throwable
    }

    /** A representation of an asynchronous [Value], backed by a Deferred */
    @JvmInline
    private value class AsyncDeferred<T>(val deferred: Deferred<T>) : Value<T> {
        override fun <U> map(block: (T) -> U): Value<U> = fromDeferred(deferred.thenApply(block))

        override fun <U> flatMap(block: (T) -> Value<U>): Value<U> =
            fromDeferred(
                deferred.thenCompose { v ->
                    when (val composeWith = block(v)) {
                        is SyncThrow -> throw composeWith.throwable
                        is SyncValue -> completedDeferred(composeWith.value)
                        is AsyncDeferred -> composeWith.deferred
                    }
                }
            )

        override fun asDeferred() = deferred

        override suspend fun await() = deferred.await()

        @Suppress("UNCHECKED_CAST")
        override suspend fun awaitOrElse(block: (Throwable) -> @UnsafeVariance T): T = thenApply { v, e -> if (e != null) block(e) else v as T }.await()

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun getCompleted(): T = deferred.getCompleted()

        override fun recover(block: (Throwable) -> Value<T>): Value<T> =
            fromDeferred(
                deferred.exceptionallyCompose { e ->
                    when (val composeWith = block(e)) {
                        is SyncThrow -> exceptionalDeferred(composeWith.throwable)
                        is SyncValue -> completedDeferred(composeWith.value)
                        is AsyncDeferred -> composeWith.deferred
                    }
                }
            )

        override fun <U> thenApply(block: (T?, Throwable?) -> U): Value<U> = fromDeferred(deferred.handle(block))

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun onCompletion(block: (T?, Throwable?) -> Unit) {
            val caller = CompletionCaller.capture()
            // An async wrapper can finish before observation; avoid allocating a completion handler in that case.
            if (deferred.isCompleted) {
                val cause = deferred.getCompletionExceptionOrNull()
                if (caller.controlFlowCancellation(cause) != null) return
                deferred.invokeCompletionCallback(cause, block)
                return
            }
            // thenApply's result is parented only when the request is active at registration.
            val requestJob = currentRequestParentJobOrNull()?.takeIf { it.isActive }
            // Registration invokes immediately if completion raced with the check, so no notification is lost.
            deferred.invokeOnCompletion { cause ->
                // Preserve result-child cancellation on request cleanup without allocating that child.
                if (requestJob?.isCancelled == true) return@invokeOnCompletion
                if (caller.controlFlowCancellation(cause) != null) return@invokeOnCompletion
                deferred.invokeCompletionCallback(cause, block)
            }
        }

        override fun <U> thenCompose(block: (T?, Throwable?) -> Value<U>): Value<U> {
            if (deferred.isCompleted) {
                return fromDeferred(deferred.handle(block).thenCompose { it.asDeferred() })
            }
            return thenComposePending(block)
        }

        /**
         * Replaces `handle(block).thenCompose { it.asDeferred() }` for pending inputs. `handle` creates an
         * intermediate `Deferred<Value<U>>`, and `thenCompose` creates the final `Deferred<U>`. Both register
         * with the request parent. Completing one result directly avoids the intermediate child and the
         * extra completed deferred that `asDeferred()` creates for synchronous callback results.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun <U> thenComposePending(block: (T?, Throwable?) -> Value<U>): Value<U> {
            val caller = CompletionCaller.capture()
            // Capture the caller's active parent; completion may run under a different request context.
            val requestParent = currentRequestParentJobOrNull()?.takeIf { it.isActive }
            val result = CompletableDeferred<U>(requestParent)
            // The callback and result cancellation may run on different threads, so share the inner atomically.
            val innerRef = AtomicReference<Deferred<U>?>(null)
            result.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    // An inner stored after this handler runs is left alone, matching the original thenCompose.
                    innerRef.get()?.cancel(cause)
                }
            }

            // Keep handle's inline execution; dispatching elsewhere would change the callback's thread and context.
            deferred.invokeOnCompletion { cause ->
                // Request cleanup skips the callback; cancelling only the returned result does not.
                if (requestParent?.isCancelled == true) return@invokeOnCompletion
                // An independently cancelled source is a field outcome while its caller remains active.
                caller.controlFlowCancellation(cause)?.let {
                    result.cancel(it)
                    return@invokeOnCompletion
                }
                val next = try {
                    if (cause != null) block(null, cause) else block(deferred.getCompleted(), null)
                } catch (ex: Exception) {
                    // CompletionHandlerException reports a failed completion handler, not an ordinary field failure.
                    ex.maybeRethrowCompletionHandlerException()
                    // After request cancellation, handle ignored CompletionException(Error); propagating it here would throw the Error.
                    if (requestParent?.isCancelled != true) result.propagateUpstreamFailure(ex)
                    return@invokeOnCompletion
                }
                // Request cancellation stopped handle from passing its Value to thenCompose; result-only cancellation did not.
                if (requestParent?.isCancelled == true) return@invokeOnCompletion
                when (next) {
                    is SyncValue -> result.complete(next.value)
                    is SyncThrow -> result.propagateUpstreamFailure(next.throwable)
                    is AsyncDeferred -> {
                        val inner = next.deferred
                        innerRef.set(inner)
                        inner.invokeOnCompletion { innerCause ->
                            if (innerCause != null) {
                                result.propagateUpstreamFailure(innerCause)
                            } else {
                                try {
                                    result.complete(inner.getCompleted())
                                } catch (ex: Exception) {
                                    inner.propagateLocalFailure(result, ex)
                                }
                            }
                        }
                    }
                }
            }

            return fromDeferred(result)
        }
    }

    companion object {
        /** Create a synchronous [Value] from the provided value */
        fun <T> fromValue(value: T): Sync<T> = SyncValue(value)

        /** Create a synchronous [Value] from the provided [Throwable]. */
        fun <T> fromThrowable(throwable: Throwable): Sync<T> = SyncThrow(throwable)

        /**
         * Returns a [Value] that completes when all [values] complete.
         *
         * Fast-path: if any value is a synchronous [SyncThrow], returns that throwable immediately
         * without awaiting the remaining values. Callers that need to survive errors in individual
         * values (e.g. to store them per-field rather than propagate them) should use
         * [Value.awaitOrElse] on the result.
         */
        fun <T> waitAll(values: Collection<Value<T>>): Value<Unit> {
            if (values.isEmpty()) return fromValue(Unit)

            val deferreds = mutableListOf<Deferred<*>>()
            values.forEach { value ->
                when (value) {
                    is SyncThrow -> return fromThrowable(value.throwable)
                    is SyncValue -> Unit
                    is AsyncDeferred -> deferreds += value.deferred
                }
            }

            return if (deferreds.isEmpty()) {
                fromValue(Unit)
            } else {
                fromDeferred(waitAllDeferreds(deferreds))
            }
        }

        /**
         * Create a [Value] from the provided [Deferred].
         *
         * If the deferred is already completed successfully, returns a [SyncValue]; if it is
         * completed exceptionally, returns a [SyncThrow]. If it is completed with cancellation,
         * the result is a cancelled Async-backed Value so downstream callers still observe
         * cancellation semantics (isCancelled=true) instead of a generic exceptional Value.
         *
         * @return a Value representing the provided Deferred
         */
        @Suppress("TooGenericExceptionCaught")
        @OptIn(ExperimentalCoroutinesApi::class)
        fun <T> fromDeferred(deferred: Deferred<T>): Value<T> =
            if (deferred.isCompleted) {
                try {
                    fromValue(deferred.getCompleted())
                } catch (ex: Exception) {
                    if (ex is CancellationException && deferred.isCancelled) {
                        AsyncDeferred(cancelledDeferred(ex))
                    } else {
                        fromThrowable(ex)
                    }
                }
            } else {
                AsyncDeferred(deferred)
            }

        /** Create a [Value] from the provided [Result] */
        fun <T> fromResult(result: Result<T>): Value<T> =
            if (result.isSuccess) {
                fromValue(result.getOrThrow())
            } else {
                fromThrowable(result.exceptionOrNull()!!)
            }

        val nullValue = Value.fromValue(null)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooGenericExceptionCaught")
private inline fun <T> Deferred<T>.invokeCompletionCallback(
    cause: Throwable?,
    block: (T?, Throwable?) -> Unit
) {
    try {
        if (cause != null) block(null, cause) else block(getCompleted(), null)
    } catch (e: Exception) {
        // thenApply stores ordinary callback failures in its discarded result; preserve their suppression.
        e.maybeRethrowCompletionHandlerException()
    }
}
