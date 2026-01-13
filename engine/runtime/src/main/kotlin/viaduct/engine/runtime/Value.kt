package viaduct.engine.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.apiannotations.TestingApi
import viaduct.deferred.cancelledDeferred
import viaduct.deferred.completedDeferred
import viaduct.deferred.exceptionalDeferred
import viaduct.deferred.exceptionallyCompose
import viaduct.deferred.handle
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

        override fun <U> thenApply(block: (T?, Throwable?) -> U): Value<U> = SyncValue(block(value, null))

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

        override fun <U> thenApply(block: (T?, Throwable?) -> U): Value<U> = SyncValue(block(null, throwable))

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

        override fun <U> thenCompose(block: (T?, Throwable?) -> Value<U>): Value<U> = fromDeferred(deferred.handle(block).thenCompose { it.asDeferred() })
    }

    companion object {
        /** Create a synchronous [Value] from the provided value */
        fun <T> fromValue(value: T): Sync<T> = SyncValue(value)

        /** Create a synchronous [Value] from the provided [Throwable]. */
        fun <T> fromThrowable(throwable: Throwable): Sync<T> = SyncThrow(throwable)

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
        @TestingApi
        fun <T> fromResult(result: Result<T>): Value<T> =
            if (result.isSuccess) {
                fromValue(result.getOrThrow())
            } else {
                fromThrowable(result.exceptionOrNull()!!)
            }

        val nullValue = Value.fromValue(null)
    }
}
