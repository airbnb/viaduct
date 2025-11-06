@file:Suppress("Detekt.TooGenericExceptionCaught")
@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.deferred

import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    } catch (e: Throwable) {
        handler(null, e)
    }
}

fun <T> Result<T>.toDeferred(): Deferred<T> {
    val d = completableDeferred<T>()
    if (this.isSuccess) {
        d.complete(this.getOrThrow())
    } else {
        d.completeExceptionally(this.exceptionOrNull()!!)
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
        } catch (ex: Throwable) {
            when (ex) {
                is CancellationException -> cancelledDeferred(ex)
                else -> exceptionalDeferred(ex)
            }
        }
    }

    val d = completableDeferred<R>()
    this.invokeOnCompletion { throwable ->
        if (throwable != null) {
            d.completeExceptionally(throwable)
        } else {
            try {
                d.complete(transform(this.getCompleted()))
            } catch (ex: Throwable) {
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
        return try {
            if (failure == null) {
                // success
                completedDeferred(handler(getCompleted(), null))
            } else {
                // exceptional (includes CancellationException); defer to handler
                completedDeferred(handler(null, failure))
            }
        } catch (e: Throwable) {
            // if the handler itself throws, propagate that
            exceptionalDeferred(e)
        }
    }

    val d = completableDeferred<U>()
    this.invokeOnCompletion { throwable ->
        val transformed = if (throwable != null) {
            try {
                handler(null, throwable)
            } catch (e: Throwable) {
                d.completeExceptionally(e)
                return@invokeOnCompletion
            }
        } else {
            try {
                handler(this.getCompleted(), null)
            } catch (e: Throwable) {
                d.completeExceptionally(e)
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
        } catch (ex: Throwable) {
            when (ex) {
                is CancellationException -> cancelledDeferred(ex)
                else -> exceptionalDeferred(ex)
            }
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
            // Propagate cancellation distinctly
            if (outerCause is CancellationException) {
                d.cancel(outerCause)
            } else {
                d.completeExceptionally(outerCause)
            }
            return@invokeOnCompletion
        }

        // Outer completed successfully
        val value: T = try {
            this.getCompleted()
        } catch (ex: Throwable) {
            // Extremely defensive: getCompleted() should succeed here, but just in case
            if (ex is CancellationException) d.cancel(ex) else d.completeExceptionally(ex)
            return@invokeOnCompletion
        }

        val inner: Deferred<R> = try {
            fn(value)
        } catch (ex: Throwable) {
            if (ex is CancellationException) d.cancel(ex) else d.completeExceptionally(ex)
            return@invokeOnCompletion
        }.also { created -> innerRef.set(created) }

        inner.invokeOnCompletion { innerCause ->
            if (innerCause != null) {
                if (innerCause is CancellationException) {
                    d.cancel(innerCause)
                } else {
                    d.completeExceptionally(innerCause)
                }
            } else {
                try {
                    d.complete(inner.getCompleted())
                } catch (ex: Throwable) {
                    // Shouldn't happen if innerCause == null, but be safe
                    if (ex is CancellationException) d.cancel(ex) else d.completeExceptionally(ex)
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
        } catch (ex: Throwable) {
            // Do NOT recover cancellations; propagate them
            when (ex) {
                is CancellationException -> cancelledDeferred(ex)
                else -> try {
                    completedDeferred(fallback(ex))
                } catch (e: Throwable) {
                    if (e is CancellationException) cancelledDeferred(e) else exceptionalDeferred<T>(e)
                }
            }
        }
    }

    val d = completableDeferred<T>()
    invokeOnCompletion { throwable ->
        if (throwable != null) {
            try {
                d.complete(fallback(throwable))
            } catch (ex: Throwable) {
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
        } catch (ex: Throwable) {
            when (ex) {
                is CancellationException -> cancelledDeferred(ex) // propagate, don't recover
                else -> try {
                    fallback(ex)
                } catch (e: Throwable) {
                    if (e is CancellationException) cancelledDeferred(e) else exceptionalDeferred<T>(e)
                }
            }
        }
    }

    val d = completableDeferred<T>()
    val fbRef = AtomicReference<Deferred<T>?>(null)

    // If caller cancels d, cancel the fallback to avoid leaks.
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
            } catch (e: Throwable) {
                if (e is CancellationException) d.cancel(e) else d.completeExceptionally(e)
            }
            return@invokeOnCompletion
        }

        // Outer completed with failure or cancellation
        if (cause is CancellationException) {
            // Do not recover cancellations; propagate them
            d.cancel(cause)
            return@invokeOnCompletion
        }

        // Failure (non-cancellation): run fallback
        val fb = try {
            fallback(cause)
        } catch (e: Throwable) {
            if (e is CancellationException) d.cancel(e) else d.completeExceptionally(e)
            return@invokeOnCompletion
        }.also { fbRef.set(it) }

        fb.invokeOnCompletion { fbCause ->
            if (fbCause == null) {
                try {
                    d.complete(fb.getCompleted())
                } catch (e: Throwable) {
                    if (e is CancellationException) d.cancel(e) else d.completeExceptionally(e)
                }
            } else {
                if (fbCause is CancellationException) d.cancel(fbCause) else d.completeExceptionally(fbCause)
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
 * Convert a CompletionStage to a Deferred. The returned Deferred will attempt to
 * cancel the underlying CompletionStage if it is cancelled.
 */
fun <T> CompletionStage<T>.asDeferred(): Deferred<T> {
    val f = toCompletableFuture()

    // fast path
    if (f.isDone) {
        return try {
            completedDeferred(f.get())
        } catch (ex: Throwable) {
            val original = (ex as? ExecutionException)?.cause ?: ex
            exceptionalDeferred(original)
        }
    }

    val d = completableDeferred<T>().apply {
        // Cancellation: if the Deferred is cancelled, attempt to cancel the underlying future
        invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                // Best-effort; safe for both CF and other stages backed by CF
                try {
                    f.cancel(true)
                } catch (_: Throwable) {
                }
            }
        }
    }

    // slow path, wait
    this.whenComplete { value, ex ->
        if (ex == null) {
            d.complete(value)
        } else {
            d.completeExceptionally(ex)
        }
    }

    return d
}
