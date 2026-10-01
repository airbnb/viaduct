package viaduct.engine.runtime2.model

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

class UncompletedPromiseException : IllegalStateException("Promise has not been completed")

/**
 * A write-once value that may be available immediately or completed later; equality is undefined.
 * A deferred implementation allocates coroutine wait state only when [await] precedes completion.
 */
sealed interface Promise<T> {
    /** Whether this promise has completed, including completion with a null value. */
    val isCompleted: Boolean

    suspend fun await(): T

    /** @throws UncompletedPromiseException when this promise has not been completed */
    fun get(): T

    /** Atomically completes this promise, returning whether this call performed the transition. */
    fun complete(value: T): Boolean

    /** Atomically fails this promise, returning whether this call performed the transition. */
    fun fail(cause: Exception): Boolean

    /** Atomically cancels this promise, returning whether this call performed the transition. */
    fun cancel(cause: CancellationException): Boolean

    companion object {
        fun <T> of(value: T): Promise<T> = CompletedPromiseImpl(value)

        fun <T> ofDeferred(): Promise<T> = DeferredPromiseImpl()
    }
}

private class CompletedPromiseImpl<T>(private val value: T) : Promise<T> {
    override val isCompleted: Boolean
        get() = true

    override suspend fun await(): T = value

    override fun get(): T = value

    override fun complete(value: T): Boolean = false

    override fun fail(cause: Exception): Boolean = false

    override fun cancel(cause: CancellationException): Boolean = false
}

private class DeferredPromiseImpl<T> : Promise<T> {
    private val state = AtomicReference<PromiseState<T>>(PendingPromiseState)

    override val isCompleted: Boolean
        get() = state.get() is FinalPromiseState

    override suspend fun await(): T {
        while (true) {
            when (val observed = state.get()) {
                PendingPromiseState -> {
                    val waiting = WaitingPromiseState<T>(CompletableDeferred())
                    if (state.compareAndSet(observed, waiting)) return waiting.deferred.await()
                }
                is WaitingPromiseState -> return observed.deferred.await()
                is SuccessfulPromiseState -> return observed.value
                is FailedPromiseState -> throw observed.cause
            }
        }
    }

    override fun get(): T {
        return when (val observed = state.get()) {
            is SuccessfulPromiseState -> observed.value
            is FailedPromiseState -> throw observed.cause
            PendingPromiseState,
            is WaitingPromiseState,
            -> throw UncompletedPromiseException()
        }
    }

    override fun complete(value: T): Boolean {
        return finish(SuccessfulPromiseState(value)) { waiting ->
            waiting.deferred.complete(value)
        }
    }

    override fun fail(cause: Exception): Boolean =
        finish(FailedPromiseState(cause)) { waiting ->
            waiting.deferred.completeExceptionally(cause)
        }

    override fun cancel(cause: CancellationException): Boolean = fail(cause)

    private inline fun finish(
        final: FinalPromiseState<T>,
        wake: (WaitingPromiseState<T>) -> Unit,
    ): Boolean {
        while (true) {
            when (val observed = state.get()) {
                is FinalPromiseState -> return false
                PendingPromiseState -> if (state.compareAndSet(observed, final)) return true
                is WaitingPromiseState ->
                    if (state.compareAndSet(observed, final)) {
                        wake(observed)
                        return true
                    }
            }
        }
    }
}

private sealed interface PromiseState<out T>

private data object PendingPromiseState : PromiseState<Nothing>

private class WaitingPromiseState<T>(
    val deferred: CompletableDeferred<T>,
) : PromiseState<T>

private sealed interface FinalPromiseState<out T> : PromiseState<T>

private class SuccessfulPromiseState<T>(
    val value: T,
) : FinalPromiseState<T>

private class FailedPromiseState(
    val cause: Exception,
) : FinalPromiseState<Nothing>

/** A promise whose producer may be assigned after readers have begun waiting for its value. */
sealed interface ReservablePromise<T> : Promise<T> {
    /** Whether a producer has claimed responsibility for completing this promise. */
    val isClaimed: Boolean

    /** Claims this promise for exactly one producer and returns the same promise. */
    fun claim(): ReservablePromise<T>

    /**
     * Claims and completes this promise immediately.
     *
     * @return whether this call completed the promise
     */
    fun set(value: T): Boolean

    /**
     * Prevents future claims and fails an existing unclaimed reservation.
     *
     * @return its argument if the invocation installed its argument as the failure, or null when
     * already claimed
     */
    fun freeze(cause: () -> Exception): Exception?
}

internal fun <T> reservablePromise(
    mutable: Boolean,
    validate: (T) -> Unit = {},
): ReservablePromise<T> = ReservablePromiseImpl(mutable, validate)

internal fun <T> completedReservablePromise(
    value: T,
    mutable: Boolean,
    validate: (T) -> Unit = {},
): ReservablePromise<T> = ReservablePromiseImpl.completed(value, mutable, validate)

private class ReservablePromiseImpl<T>(
    private val mutable: Boolean,
    private val delegate: Promise<T>,
    initiallyClaimed: Boolean,
    private val validate: (T) -> Unit,
) : ReservablePromise<T> {
    private val lock = Any()
    private var claimed = initiallyClaimed
    private var frozen = !mutable

    constructor(
        mutable: Boolean,
        validate: (T) -> Unit,
    ) : this(
        mutable = mutable,
        delegate = Promise.ofDeferred(),
        initiallyClaimed = false,
        validate = validate,
    )

    override val isClaimed: Boolean
        get() = synchronized(lock) { claimed }

    override val isCompleted: Boolean
        get() = delegate.isCompleted

    override suspend fun await(): T = delegate.await()

    override fun get(): T = delegate.get()

    override fun claim(): ReservablePromise<T> {
        synchronized(lock) {
            check(!frozen) { "Promise is frozen" }
            check(!claimed) { "Promise already has a producer" }
            claimed = true
        }
        return this
    }

    override fun set(value: T): Boolean {
        validate(value)
        claim()
        return delegate.complete(value)
    }

    override fun complete(value: T): Boolean {
        checkClaimed()
        validate(value)
        return delegate.complete(value)
    }

    override fun fail(cause: Exception): Boolean {
        checkClaimed()
        return delegate.fail(cause)
    }

    override fun cancel(cause: CancellationException): Boolean {
        checkClaimed()
        return delegate.cancel(cause)
    }

    override fun freeze(cause: () -> Exception): Exception? {
        val failReservation =
            synchronized(lock) {
                check(mutable) { "Promise is immutable" }
                check(!frozen) { "Promise is already frozen" }
                frozen = true
                !claimed
            }
        return if (failReservation) {
            cause().also { failure -> check(delegate.fail(failure)) }
        } else {
            null
        }
    }

    private fun checkClaimed() {
        synchronized(lock) {
            check(claimed) { "Promise has no producer" }
        }
    }

    companion object {
        fun <T> completed(
            value: T,
            mutable: Boolean,
            validate: (T) -> Unit,
        ): ReservablePromise<T> {
            validate(value)
            return ReservablePromiseImpl(
                mutable = mutable,
                delegate = Promise.of(value),
                initiallyClaimed = true,
                validate = validate,
            )
        }
    }
}
