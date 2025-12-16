@file:Suppress("Detekt.TooGenericExceptionCaught")
@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.deferred

import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletionHandlerException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Create a CompletableDeferred with the parent job set to the current threadLocalCoroutineContext's Job
 */
fun <T> completableDeferred(): CompletableDeferred<T> {
    val parentJob = threadLocalCurrentJobOrNull()
        ?: return CompletableDeferred()
    if (!parentJob.isActive) {
        return CompletableDeferred()
    }
    val supervisor = SupervisorJob(parentJob)
    return CompletableDeferred<T>(parent = supervisor).apply {
        invokeOnCompletion {
            supervisor.complete()
        }
    }
}

fun <T> completedDeferred(value: T): CompletableDeferred<T> {
    // don't need a parent for a completed deferred
    val d = CompletableDeferred<T>()
    d.complete(value)
    return d
}

/** create a Deferred in an exceptional completed state */
fun <T> exceptionalDeferred(ex: Throwable): CompletableDeferred<T> {
    // don't need a parent for a completed deferred
    val d = CompletableDeferred<T>()
    d.completeExceptionally(ex)
    return d
}

fun <T> cancelledDeferred(cause: CancellationException): CompletableDeferred<T> = CompletableDeferred<T>().apply { cancel(cause) }

fun <T : Any?> handle(
    block: () -> T,
    handler: (Any?, Throwable?) -> Any?
): Any? {
    @Suppress("UNCHECKED_CAST")
    return try {
        val result = block()
        if (result is Deferred<*>) {
            result as Deferred<T>
            result.handle(handler)
        } else {
            handler(result, null)
        }
    } catch (ex: Exception) {
        ex.maybeRethrowCompletionHandlerException()
        handler(null, ex)
    }
}

fun <T> Result<T>.toDeferred(): Deferred<T> {
    val d = completableDeferred<T>()
    if (this.isSuccess) {
        d.complete(this.getOrThrow())
    } else {
        d.propagateUpstreamFailure(this.exceptionOrNull())
    }
    return d
}

/**
 * Returns a new [Deferred] that, when this [Deferred] completes normally, is executed
 * with this [Deferred]'s result as the argument to [transform]. If this
 * [Deferred] completes exceptionally, the return value will also complete exceptionally
 * and [transform] will not be called.
 */
inline fun <T, R> Deferred<T>.thenApply(crossinline transform: (T) -> R): Deferred<R> {
    // fast path
    if (isCompleted) {
        return try {
            completedDeferred(transform(this.getCompleted()))
        } catch (ex: Exception) {
            if (ex is CancellationException && this.isCancelled) {
                return cancelledDeferred(ex)
            }
            ex.maybeRethrowCompletionHandlerException()
            exceptionalDeferred(ex)
        }
    }

    val d = completableDeferred<R>()
    this.invokeOnCompletion { throwable ->
        if (throwable != null) {
            d.propagateUpstreamFailure(throwable)
        } else {
            try {
                d.complete(transform(this.getCompleted()))
            } catch (ex: Exception) {
                ex.maybeRethrowCompletionHandlerException()
                d.completeExceptionally(ex)
            }
        }
    }
    return d
}

fun <T : Any?, U : Any?> Deferred<T>.handle(handler: (T?, Throwable?) -> U): Deferred<U> {
    // fast path: already completed (either successfully or exceptionally)
    if (isCompleted) {
        val failure = getCompletionExceptionOrNull()
        if (failure is CancellationException && this.isCancelled) {
            return cancelledDeferred(failure)
        }
        if (failure != null) {
            return try {
                completedDeferred(handler(null, failure))
            } catch (ex: Exception) {
                ex.maybeRethrowCompletionHandlerException()
                exceptionalDeferred(ex)
            }
        }
        return try {
            completedDeferred(handler(getCompleted(), null))
        } catch (ex: Exception) {
            ex.maybeRethrowCompletionHandlerException()
            exceptionalDeferred(ex)
        }
    }

    val d = completableDeferred<U>()
    this.invokeOnCompletion { throwable ->
        val transformed = if (throwable != null) {
            if (throwable is CancellationException && this.isCancelled) {
                d.cancel(throwable)
                return@invokeOnCompletion
            }
            try {
                handler(null, throwable)
            } catch (ex: Exception) {
                ex.maybeRethrowCompletionHandlerException()
                d.completeExceptionally(ex)
                return@invokeOnCompletion
            }
        } else {
            try {
                handler(this.getCompleted(), null)
            } catch (ex: Exception) {
                ex.maybeRethrowCompletionHandlerException()
                d.completeExceptionally(ex)
                return@invokeOnCompletion
            }
        }
        d.complete(transformed)
    }
    return d
}

/**
 * Returns a new [Deferred] that, when this [Deferred] completes normally, is executed
 * with this [Deferred]'s result as the argument to [fn]. If this [Deferred] completes
 * exceptionally, the return value will also complete exceptionally and [transform]
 * will not be called.
 */
fun <T, R> Deferred<T>.thenCompose(fn: (T) -> Deferred<R>): Deferred<R> {
    // fast path
    if (isCompleted) {
        return try {
            fn(getCompleted())
        } catch (ex: Exception) {
            if (ex is CancellationException && this.isCancelled) {
                return cancelledDeferred(ex)
            }
            ex.maybeRethrowCompletionHandlerException()
            exceptionalDeferred(ex)
        }
    }

    val d = completableDeferred<R>()

    // Keep a ref so we can cancel inner if d is cancelled.
    val innerRef = AtomicReference<Deferred<R>?>(null)

    // If d is cancelled by the caller, cancel inner to avoid leaks.
    d.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
            innerRef.get()?.cancel(cause)
        }
    }

    this.invokeOnCompletion { outerCause ->
        if (outerCause != null) {
            d.propagateUpstreamFailure(outerCause)
            return@invokeOnCompletion
        }

        // Outer completed successfully
        val value: T = try {
            this.getCompleted()
        } catch (ex: Exception) {
            // Extremely defensive: getCompleted() should succeed here, but just in case
            this.propagateLocalFailure(d, ex)
            return@invokeOnCompletion
        }

        val inner: Deferred<R> = try {
            fn(value)
        } catch (ex: Exception) {
            ex.maybeRethrowCompletionHandlerException()
            d.completeExceptionally(ex)
            return@invokeOnCompletion
        }.also { created -> innerRef.set(created) }

        inner.invokeOnCompletion { innerCause ->
            if (innerCause != null) {
                d.propagateUpstreamFailure(innerCause)
            } else {
                try {
                    d.complete(inner.getCompleted())
                } catch (ex: Exception) {
                    // Shouldn't happen if innerCause == null, but be safe
                    inner.propagateLocalFailure(d, ex)
                }
            }
        }
    }

    return d
}

fun <T> Deferred<T>.exceptionally(fallback: (Throwable) -> T): Deferred<T> {
    // fast path
    if (isCompleted) {
        return try {
            completedDeferred(getCompleted())
        } catch (ex: Exception) {
            if (ex is CancellationException) {
                return cancelledDeferred(ex)
            }
            return try {
                completedDeferred(fallback(ex))
            } catch (fallbackEx: Exception) {
                fallbackEx.maybeRethrowCompletionHandlerException()
                exceptionalDeferred<T>(fallbackEx)
            }
        }
    }

    val d = completableDeferred<T>()
    invokeOnCompletion { throwable ->
        if (throwable != null) {
            if (throwable is CancellationException && this.isCancelled) {
                d.cancel(throwable)
                return@invokeOnCompletion
            }
            try {
                d.complete(fallback(throwable))
            } catch (ex: Exception) {
                ex.maybeRethrowCompletionHandlerException()
                d.completeExceptionally(ex)
            }
        } else {
            d.complete(this.getCompleted())
        }
    }
    return d
}

fun <T> Deferred<T>.exceptionallyCompose(fallback: (Throwable) -> Deferred<T>): Deferred<T> {
    if (isCompleted) {
        return try {
            completedDeferred(getCompleted())
        } catch (ex: Exception) {
            if (ex is CancellationException) {
                return cancelledDeferred(ex)
            }
            return try {
                fallback(ex)
            } catch (fallbackEx: Exception) {
                fallbackEx.maybeRethrowCompletionHandlerException()
                exceptionalDeferred<T>(fallbackEx)
            }
        }
    }

    val d = completableDeferred<T>()
    val fbRef = AtomicReference<Deferred<T>?>(null)

    // If d is cancelled by the caller, cancel fbRef to avoid leaks.
    d.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
            fbRef.get()?.cancel(cause)
        }
    }

    this.invokeOnCompletion { cause ->
        if (cause == null) {
            // Outer completed successfully
            try {
                d.complete(this.getCompleted())
            } catch (ex: Exception) {
                this.propagateLocalFailure(d, ex)
            }
            return@invokeOnCompletion
        }

        if (cause is CancellationException && this.isCancelled) {
            d.cancel(cause)
            return@invokeOnCompletion
        }

        // Failure (non-cancellation): run fallback
        val fb = try {
            fallback(cause)
        } catch (ex: Exception) {
            ex.maybeRethrowCompletionHandlerException()
            d.completeExceptionally(ex)
            return@invokeOnCompletion
        }.also { fbRef.set(it) }

        fb.invokeOnCompletion { fbCause ->
            if (fbCause == null) {
                try {
                    d.complete(fb.getCompleted())
                } catch (ex: Exception) {
                    fb.propagateLocalFailure(d, ex)
                }
            } else {
                d.propagateUpstreamFailure(fbCause)
            }
        }
    }

    return d
}

/**
 * Composes two Deferred values by returning a new Deferred that:
 * 1) Awaits completion of this Deferred, then
 * 2) Invokes [combiner] with this Deferred's successful result and awaits [other],
 * 3) Completes with the result of [combiner].
 *
 * Because [other] is started independently, it may be running in parallel with this Deferred,
 * and might even finish first. This method will still yield the correct combined result, but
 * the callback for [other] is only attached *after* this Deferred completes. In other words,
 * we are chaining the completions rather than registering a single “two-input” callback up front.
 *
 * **Trade-offs and limitations**:
 * - We do not unify lifecycle or cancellation across the two Deferreds. If one fails or is canceled,
 *   that does not automatically stop the other. Exception propagation still works as expected for
 *   the final result, but we don’t provide sophisticated cancellation behavior.
 * - This approach is much simpler than a dedicated “both-input” concurrency construct (such as a
 *   `CompletableFuture`-style BiCompletion or Kotlin’s `awaitAll`), which can do things like
 *   cancel the other input on failure or attach callbacks to both simultaneously.
 *
 * A future implementation may address these tradeoffs, but for most straightforward needs—
 * where the two Deferreds are already running independently and you just want to combine their
 * eventual results—this is perfectly fine.
 */
fun <T, U, R> Deferred<T>.thenCombine(
    other: Deferred<U>,
    combiner: (T, U) -> R
): Deferred<R> =
    this.thenCompose { t ->
        other.thenApply { u -> combiner(t, u) }
    }

/**
 * Convert a CompletionStage to a parented Deferred. We implement our own version instead of
 * kotlinx-coroutines' `asDeferred` so the resulting Deferred is tied into our job hierarchy and
 * propagates cancellation back to the underlying CompletionStage.
 */
fun <T> CompletionStage<T>.asDeferred(): Deferred<T> {
    val f = toCompletableFuture()

    // fast path
    if (f.isDone) {
        return try {
            completedDeferred(f.get())
        } catch (ex: Exception) {
            val original = (ex as? ExecutionException)?.cause ?: ex
            when (original) {
                is CancellationException -> cancelledDeferred(original)
                is Exception -> exceptionalDeferred(original)
                else -> throw original
            }
        }
    }

    val d = completableDeferred<T>().apply {
        // Cancellation: if the Deferred is cancelled, attempt to cancel the underlying future
        invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                // Best-effort; safe for both CF and other stages backed by CF
                try {
                    f.cancel(true)
                } catch (ex: Exception) {
                    try {
                        ex.maybeRethrowCompletionHandlerException()
                    } catch (cancel: CancellationException) {
                        // swallow CancellationException triggered while canceling the future
                    }
                }
            }
        }
    }

    // slow path, wait
    this.whenComplete { value, ex ->
        if (ex == null) {
            d.complete(value)
        } else {
            d.propagateUpstreamFailure(ex)
        }
    }

    return d
}

/**
 * Waits for every Deferred in [deferreds] to finish.
 *
 * All inputs are allowed to complete (no fail-fast cancellation). If any input fails, the first
 * failure is propagated after all inputs have completed.
 *
 * @param deferreds inputs to synchronize
 * @return a Deferred that completes when all inputs finish successfully or propagates the first failure
 */
fun waitAllDeferreds(deferreds: Collection<Deferred<*>>): Deferred<Unit> {
    if (deferreds.isEmpty()) return completedDeferred(Unit)

    if (deferreds.all { it.isCompleted }) {
        return try {
            deferreds.forEach { it.getCompleted() }
            completedDeferred(Unit)
        } catch (ex: Exception) {
            ex.maybeRethrowCompletionHandlerException()
            when (ex) {
                is CancellationException -> cancelledDeferred(ex)
                else -> exceptionalDeferred(ex)
            }
        }
    }

    val result = completableDeferred<Unit>()
    val remaining = AtomicInteger(deferreds.size)
    val firstFailure = AtomicReference<Throwable?>(null)

    result.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
            deferreds.forEach { d ->
                if (!d.isCompleted) {
                    try {
                        d.cancel(cause)
                    } catch (ex: Exception) {
                        try {
                            ex.maybeRethrowCompletionHandlerException()
                        } catch (cancel: CancellationException) {
                            // already cancelling; swallow cancellation exceptions here
                        }
                    }
                }
            }
        }
    }

    deferreds.forEach { d ->
        d.invokeOnCompletion { cause ->
            if (cause != null) {
                firstFailure.compareAndSet(null, cause)
            }
            if (remaining.decrementAndGet() == 0) {
                val failure = firstFailure.get()
                if (failure != null) {
                    when (failure) {
                        is CancellationException -> result.cancel(failure)
                        else -> result.completeExceptionally(failure)
                    }
                } else {
                    result.complete(Unit)
                }
            }
        }
    }

    return result
}

/**
 * Re-throws `CompletionHandlerException` so we're not accidentally swallowing critical coroutines failures.
 * @receiver the exception to inspect for coroutine-specific types that must be rethrown
 */
@OptIn(InternalCoroutinesApi::class)
fun Exception.maybeRethrowCompletionHandlerException() {
    if (this is CompletionHandlerException) {
        throw this
    }
}

/**
 * Treat a throwable thrown inside the current coroutine as either a cancellation signal or a
 * regular exception based on the provided (or thread-local) job's state.
 */
@OptIn(InternalCoroutinesApi::class)
inline fun <T> Throwable.handleLocalCancellation(
    job: Job,
    onCancellation: (CancellationException) -> T,
    onNonCancellation: (Exception) -> T
): T =
    when (this) {
        is CancellationException -> {
            if (job.isCancelled) {
                onCancellation(this)
            } else {
                onNonCancellation(this)
            }
        }

        is Exception -> {
            maybeRethrowCompletionHandlerException()
            onNonCancellation(this)
        }

        else -> throw this // Errors should propagate immediately
    }

/**
 * Finish this deferred with an exception that was thrown inside its own coroutine context.
 *
 * If the deferred's job is already cancelled we propagate that cancellation; otherwise we surface
 * the throwable as a normal exceptional completion.
 */
fun Deferred<*>.propagateLocalFailure(
    target: CompletableDeferred<*>,
    error: Throwable
) {
    error.handleLocalCancellation(this, { cancel ->
        target.cancel(cancel)
    }) { ex ->
        target.completeExceptionally(ex)
    }
}

/**
 * Relay a failure that originated upstream (another coroutine, CompletionStage, etc.).
 *
 * CancellationExceptions are always forwarded as cancellations, while other throwables simply
 * complete this deferred exceptionally. CompletionHandlerExceptions are rethrown so they are not
 * swallowed, and we never catch [Error] types.
 */
@OptIn(InternalCoroutinesApi::class)
fun CompletableDeferred<*>.propagateUpstreamFailure(failure: Throwable?) {
    when (failure) {
        null -> return
        is CompletionHandlerException -> throw failure
        is CancellationException -> cancel(failure)
        is Exception -> completeExceptionally(failure)
        else -> throw failure
    }
}
