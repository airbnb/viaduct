@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.deferred.completedDeferred
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.FieldDirectives

/**
 * Thread-safe data structure for memoizing field resolution results during GraphQL query execution.
 * See [Cell] for more about thread safety guarantees.
 *
 * It is designed to handle the following GraphQL execution patterns:
 *
 * 1. Multiple resolvers may attempt to resolve the same field concurrently
 * 2. Resolvers may request field values before they are computed
 * 3. Field values, once set, are immutable
 *
 * The [ObjectEngineResultImpl] has a "lazy resolution state". It can start in either a complete or incomplete state,
 * represented using a [Deferred] value.
 *
 * Usage example:
 * ```
 * // Writer:
 * ```
 * val result = engine.computeIfAbsent(key) { slotSetter ->
 *     slotSetter.setRaw(computeValue())
 *     slotSetter.setAccessCheck(computeAccessCheckValue())
 * }
 * ```
 *
 * // Reader:
 * val value = engine.fetch(key, ACCESS_CHECK_SLOT) // Suspends if value not yet available
 * ```
 *
 * @see fetch For reading values
 * @see computeIfAbsent For the combined claim/compute/complete operation
 */
class ObjectEngineResultImpl private constructor(
    override val type: GraphQLObjectType,
    val pending: Boolean = false,
    val matSource: MatSource? = null,
) : ObjectEngineResult {
    private val storage = ConcurrentHashMap<ObjectEngineResult.Key, Cell>()

    /**
     * The engine supports unresolved lazy references (e.g. for nodes and root fields).
     * When a resolver returns a lazy reference, we "optimistically" allocate an OER for it to
     * store any already-resolved data (e.g. node id) and to indicate that it's pending resolution.
     *
     * During resolution the pending resolver is called in parallel with field-resolvers on that
     * object, meaning fields of this this OER might get filled out with values even though,
     * eventually, if the pending resolver fails, we're going to discard those results and treat
     * the entire field containing the OER as failed.
     *
     * A key observation here is that during resolution, it's okay to treat the OER as if it's
     * going to be successful (we're "optimistic" during resolution). However, during completion,
     * if the pending resolver has failed, we need to treat the field containing the OER as if it
     * failed (and discard any results we optimistically resolved).
     *
     * To implement these semantics, OERs have a property called [lazyResolutionState], which is a CompletableDeferred.
     * While [lazyResolutionState] is uncompleted, we say that the OER is "pending", which means its in its "optimistic"
     * phase and fetches of its fields return normally. However, once the node resolver returns,
     * [lazyResolutionState] is completed to one of three states:
     * - [LazyResolutionResult.VALUE]: resolution succeeded and the OER contains valid data
     * - [LazyResolutionResult.NULL]: resolution succeeded and this object resolved to null. This OER should be discarded
     *      during completion.
     * - Exceptionally: resolution failed (fetches of the OER's fields will fail)
     */
    internal val lazyResolutionState: CompletableDeferred<LazyResolutionResult> = let {
        if (pending) {
            // create a new pending deferred
            CompletableDeferred()
        } else {
            // otherwise, create a pre-completed deferred
            completedDeferred(LazyResolutionResult.VALUE)
        }
    }

    /**
     * A deferred that is completed when all fields of this object have been resolved
     * (fetched and cells populated), but not necessarily when all nested objects have
     * finished resolving.
     *
     * This acts as a barrier for [FieldCompleter]: completion of this object should
     * not start until this barrier is open, ensuring that synchronous values are
     * already present in [Cell]s, avoiding the need for [CompletableDeferred]s in
     * the [Cell] read path.
     */
    val fieldResolutionState: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * Fetches the value in slot number [slotNo] for the field, suspending if not yet available.
     *
     * @param key The key to fetch
     * @return The value for the field.
     */
    override suspend fun fetch(
        key: ObjectEngineResult.Key,
        slotNo: Int
    ): Any? {
        return getValue(key, slotNo).await()
    }

    /**
     * Gets the [Value] in slot number [slotNo] for the field.
     */
    internal fun getValue(
        key: ObjectEngineResult.Key,
        slotNo: Int
    ): Value<*> {
        if (lazyResolutionState.isCompleted) {
            lazyResolutionState.getCompletionExceptionOrNull()?.let {
                return Value.fromThrowable<Nothing>(it)
            }
            if (isResolvedToNull()) {
                return Value.nullValue
            }
        }
        // It's possible that [lazyResolutionState] is completed between the isCompleted check above
        // and here. It's only in the "optimistic" case during resolution that this can
        // occur. During field completion, we "pessimistically" wait for nodeResolutionState to complete
        // before retrieving the value, so there is no race condition.
        return maybeInitializeKey(key).getValue(slotNo)
    }

    /**
     * Computes a value if not already present, or returns the existing value.
     *
     * This method ensures that only one computation will occur for a given key,
     * even under concurrent access. If multiple coroutines attempt to compute
     * the same key simultaneously:
     * - One will claim the key and compute the value
     * - Others will wait for and return the computed value
     *
     * @param key The key to compute or fetch
     * @param block A function that sets each slot with the computed [Value]
     * @return A [Value] containing either the computed or existing value in the RAW_VALUE_SLOT
     */
    internal fun computeIfAbsent(
        key: ObjectEngineResult.Key,
        block: (SlotSetter) -> Unit
    ): Value<*> {
        return maybeInitializeKey(key)
            .computeIfAbsent(block)
            .getValue(RAW_VALUE_SLOT)
    }

    /**
     * Waits for this OER to finish resolving, and returns the error if it resolved exceptionally
     * or null if it resolved successfully.
     */
    internal suspend fun resolvedExceptionOrNull(): Throwable? {
        return runCatching { lazyResolutionState.await() }.exceptionOrNull()
    }

    /**
     * Returns true if this OER resolved to null. [lazyResolutionState] must be completed before calling this method.
     */
    internal fun isResolvedToNull(): Boolean {
        check(lazyResolutionState.isCompleted) { "Cannot call isResolvedToNull before resolution completes" }
        return lazyResolutionState.getCompleted() == LazyResolutionResult.NULL
    }

    /**
     * Initializes a field with an unwritten cell.
     *
     * @param key The key to initialize
     * @return Cell associated with this key (which may have been newly created).
     */
    private fun maybeInitializeKey(key: ObjectEngineResult.Key): Cell = storage.computeIfAbsent(key) { newCell() }

    /**
     * Returns the cell associated with a key independent of the state of
     * [lazyResolutionState].
     *
     * This is "optimistic" in that it returns the cell even if the OER's lazy
     * resolution has failed. Your should not use this if when your result will
     * be exposed even after lazy resolution has exposed: in those cases your
     * should use `fetch` or `getValue`.
     */
    internal fun getCellOptimistically(key: ObjectEngineResult.Key): Cell = maybeInitializeKey(key)

    /**
     * Resolves this OER exceptionally if it is in the pending state.
     * If already resolved with the same exception, this is a no-op.
     *
     * @throws IllegalStateException if this OER is already resolved normally or with a different exception
     */
    internal fun resolveExceptionally(exception: Throwable) {
        if (!lazyResolutionState.completeExceptionally(exception)) {
            val completionException = lazyResolutionState.getCompletionExceptionOrNull()
            if (completionException == exception) {
                return
            }
            // Otherwise it was resolved normally or with a different exception
            throw IllegalStateException("Invariant: already resolved", completionException)
        }
    }

    /**
     * Resolves this OER with data if it is in the pending state.
     * After this call, field accesses proceed normally.
     * If already resolved with VALUE, this is a no-op.
     *
     * @throws IllegalStateException if this OER is already resolved exceptionally or to null
     */
    internal fun resolveToValue() {
        if (!lazyResolutionState.complete(LazyResolutionResult.VALUE)) {
            lazyResolutionState.getCompletionExceptionOrNull()?.let {
                throw IllegalStateException("Invariant: already resolved exceptionally", it)
            }
            check(lazyResolutionState.getCompleted() == LazyResolutionResult.VALUE) {
                "Invariant: already resolved to null"
            }
        }
    }

    /**
     * Resolves this OER to null if it is in the pending state. This indicates the
     * underlying nullable field resolved to null (e.g., a root field reference to a
     * nullable field that returned null). After this call, field accesses return null
     * and the [FieldCompleter] will complete this object as a null value.
     *
     * @throws IllegalStateException if this OER is already resolved exceptionally or with VALUE
     */
    internal fun resolveToNull() {
        if (!lazyResolutionState.complete(LazyResolutionResult.NULL)) {
            lazyResolutionState.getCompletionExceptionOrNull()?.let {
                throw IllegalStateException("Invariant: already resolved exceptionally", it)
            }
            check(lazyResolutionState.getCompleted() == LazyResolutionResult.NULL) {
                "Invariant: already resolved with VALUE"
            }
        }
    }

    internal enum class LazyResolutionResult { VALUE, NULL }

    companion object {
        private val DEFAULT_SLOT_COUNT = 2
        val RAW_VALUE_SLOT: Int = 0
        val ACCESS_CHECK_SLOT: Int = 1

        internal fun newCell() = Cell.create(DEFAULT_SLOT_COUNT)

        internal fun newCell(block: (SlotSetter) -> Unit) = Cell.create(DEFAULT_SLOT_COUNT, block)

        internal fun SlotSetter.setRawValue(value: Value<FieldResolutionResult>) {
            this.set(RAW_VALUE_SLOT, value)
        }

        internal fun SlotSetter.setCheckerValue(value: Value<out CheckerResult?>) {
            this.set(ACCESS_CHECK_SLOT, value)
        }

        /**
         * Creates a new ObjectEngineResult for the given type in the Resolved state.
         */
        fun newForType(
            type: GraphQLObjectType,
            matSource: MatSource? = null
        ) = ObjectEngineResultImpl(type, matSource = matSource)

        /**
         * Creates a new ObjectEngineResult for the given type in the Pending state.
         */
        fun newPendingForType(
            type: GraphQLObjectType,
            matSource: MatSource? = null
        ) = ObjectEngineResultImpl(type, pending = true, matSource = matSource)
    }
}

/**
 * Fetches a field result for use by resolver code.
 *
 * Raw resolution errors take precedence over checker errors. Checker errors are evaluated using
 * [CheckerResult.Error.isErrorForResolver] and the directives from the resolver's field selection.
 */
suspend fun ObjectEngineResultImpl.fetchFieldResultForResolver(
    key: ObjectEngineResult.Key,
    fieldDirectives: FieldDirectives?,
): FieldResolutionResult {
    val fieldResult = fetch(key, ObjectEngineResultImpl.RAW_VALUE_SLOT) as? FieldResolutionResult
        ?: error("Expected raw slot for `$key` to contain a FieldResolutionResult")
    if (fieldResult.errors.isNotEmpty()) {
        throw FieldErrorsException(fieldResult.errors)
    }
    extractResolverCheckerException(
        fetch(key, ObjectEngineResultImpl.ACCESS_CHECK_SLOT),
        fieldDirectives,
    )?.let { throw it }
    return fieldResult
}

/**
 * Returns the exception from [checkerResult] when it represents an error for a resolver read.
 */
internal fun extractResolverCheckerException(
    checkerResult: Any?,
    fieldDirectives: FieldDirectives?,
): Exception? {
    checkerResult ?: return null
    if (checkerResult !is CheckerResult) {
        return IllegalStateException(
            "Expected access check slot to contain a CheckerResult, got $checkerResult"
        )
    }
    return checkerResult.asError?.let { error ->
        if (error.isErrorForResolver(CheckerResultContext(fieldDirectives = fieldDirectives))) {
            error.error
        } else {
            null
        }
    }
}
