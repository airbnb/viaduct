package viaduct.engine.runtime.execution

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.Mat
import viaduct.engine.runtime.mat.MatLedger
import viaduct.engine.runtime.mat.MatPath
import viaduct.engine.runtime.mat.MatResult
import viaduct.engine.runtime.mat.subtreeAt
import viaduct.engine.runtime.result.ObjectEngineResult

/**
 * Stores the results produced by one [Mat] and reads fields from those results.
 *
 * # Concurrency notes
 * This implementation is safe for concurrent use.
 *
 * Concurrent requests for disjoint KeyTrees will be executed in parallel. This has the effect
 * that keys in a requested KeyTree will be materialized once, though surplus key materializations
 * (ie a resolver materializes fields that were not requested) may be inconsistent.
 * In these cases, the first writer of these fields win.
 *
 * If a requested KeyTree overlaps with concurrent materializations, the request materializes any
 * unreserved keys in parallel, then waits for the overlapping materializations to complete.
 *
 * @param mat The ledger calls this value when it needs to load missing fields.
 */
internal class MatLedgerImpl(private val mat: Mat) : MatLedger {
    /**
     * Guards the ledger's materialization state.
     *
     * Materialization runs outside this mutex. In-flight reservations serialize ordinary callers
     * while allowing nested execution to materialize disjoint selections.
     */
    private val mutex = Mutex()

    @Volatile
    private var state: State = State()

    /**
     * Records the result available when this ledger is created.
     *
     * Initialization is optional, may happen at most once, and must precede materialization.
     */
    suspend fun initialize(result: MatResult) {
        mutex.withLock {
            checkUninitializedUnsafe()
            state = state.record(result)
        }
    }

    /**
     * Invokes [mat] to create and record this ledger's first result.
     *
     * Unlike [initialize], this keeps the initial invocation behind the same boundary used by
     * later materializations.
     */
    suspend fun materializeInitial(
        requested: KeyTree,
        selectionHandle: EngineExecutionContext.ExecutionHandle,
    ): MatResult {
        val reservation = mutex.withLock {
            checkUninitializedUnsafe()
            reserveUnsafe(requested)
        }
        return invokeAndRecord(reservation, selectionHandle)
    }

    /**
     * Invokes [mat] outside [mutex], then publishes the result and wakes waiting callers.
     *
     * A Mat may re-enter this ledger through nested graph work, so invoking it while holding the
     * mutex can deadlock.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun invokeAndRecord(
        reservation: Pending,
        selectionHandle: EngineExecutionContext.ExecutionHandle,
    ): MatResult {
        val result = try {
            mat(reservation.requested, selectionHandle)
        } catch (failure: Throwable) {
            completeFailure(reservation, failure)
            throw failure
        }
        completeSuccess(reservation, result)
        return result
    }

    /** Publishes a completed materialization and wakes its waiters. */
    private suspend fun completeSuccess(
        reservation: Pending,
        result: MatResult,
    ) {
        /**
         * A Mat may finish at the same moment its request is canceled.
         *
         * For example:
         * 1. Callers A and B both call ensureCoverage. Caller A starts the Mat while caller B waits.
         * 2. The Mat returns a result.
         * 3. Caller A's coroutine is canceled before the result is saved.
         *
         * In this case, we must still save the result and wake caller B. NonCancellable lets this
         * block finish even though caller A was canceled.
         */
        withContext(NonCancellable) {
            mutex.withLock {
                check(state.pendings.any { it === reservation })
                state = state.record(result).remove(reservation)
            }
            reservation.completion.complete(Unit)
        }
    }

    /** Clears a failed materialization and wakes its waiters. See [completeSuccess]. */
    private suspend fun completeFailure(
        reservation: Pending,
        failure: Throwable,
    ) {
        withContext(NonCancellable) {
            mutex.withLock {
                check(state.pendings.any { it === reservation })
                state = state.remove(reservation)
            }
            reservation.completion.completeExceptionally(failure)
        }
    }

    /**
     * Materializes any uncovered parts of a KeyTree.
     *
     * If [mat] returns failed coverage, later reads of that coverage rethrow the materialization
     * error from the field that consumes it. Exceptions thrown directly by [mat] propagate to
     * the caller.
     *
     * @param requested is the selection shape requested at the ledger root.
     * @param selectionHandle is the execution scope that supplied the requested selections.
     * @throws Exception when [mat] throws instead of returning a [MatResult].
     */
    override suspend fun ensureCoverage(
        requested: KeyTree,
        selectionHandle: EngineExecutionContext.ExecutionHandle,
    ) {
        while (true) {
            val action = mutex.withLock {
                buildCoverageActionUnsafe(requested)
            }
            when (action) {
                CoverageAction.Complete -> return
                is CoverageAction.Await -> action.promise.await()
                is CoverageAction.Invoke -> invokeAndRecord(action.pending, selectionHandle)
            }
        }
    }

    /** Chooses the next coverage action after the caller has locked [mutex]. */
    private fun buildCoverageActionUnsafe(requested: KeyTree): CoverageAction {
        val missing = requested - state.coverage

        if (missing.isEmpty()) {
            return CoverageAction.Complete
        }

        val unreserved = state.pendings.fold(missing) { remaining, pending ->
            remaining - pending.requested
        }
        if (!unreserved.isEmpty()) {
            return CoverageAction.Invoke(reserveUnsafe(unreserved))
        }

        val overlapping = checkNotNull(
            state.pendings.firstOrNull { missing - it.requested != missing }
        )
        return CoverageAction.Await(overlapping.completion)
    }

    /** Registers one [Pending] after the caller has locked [mutex]. */
    private fun reserveUnsafe(requested: KeyTree): Pending {
        check(!requested.isEmpty())
        val pending = Pending(requested)
        state = state.add(pending)
        return pending
    }

    /** Verifies initial materialization has not begun after the caller has locked [mutex]. */
    private fun checkUninitializedUnsafe() {
        check(state.results.isEmpty() && state.pendings.isEmpty()) {
            "Mat ledger for $mat is already initialized"
        }
    }

    /**
     * Resolves the source object that covers a field at a member path.
     *
     * @param path is the path to the object being read.
     * @param key is the terminal field instance that must be covered by the resolved source.
     */
    override suspend fun resolveSource(
        path: MatPath,
        key: ObjectEngineResult.Key,
    ): EngineObjectData? {
        val snapshot = state
        val expectedRootType = snapshot.rootType ?: path.rootType
        requireMaterializedType(path.rootType, expectedRootType)

        val matResult = requireMaterializedNotNull(
            snapshot.results.firstOrNull { result ->
                result.coverage.subtreeAt(path).containsKey(path.terminalType, key)
            }
        ) {
            "no mat result of $mat covers key `$key` at path ${path.segments.map { it.key }}"
        }

        var source: Any? = matResult.source.getOrThrow() ?: return null
        var expectedType = expectedRootType
        for (segment in path.segments) {
            val eod = requireMaterializedNotNull(source as? EngineObjectData) {
                "mat result of $mat diverged: expected object " +
                    "at `${segment.key.responseKey}`, found ${source?.let { it::class.simpleName }}"
            }
            requireMaterializedType(eod, expectedType)
            source = eod.fetchOrNull(segment.key.name)
            for (index in segment.indices) {
                val list = requireMaterializedNotNull(source as? List<*>) {
                    "mat result of $mat diverged: expected list at `${segment.key.responseKey}`"
                }
                if (index >= list.size) {
                    throw materializationException(
                        "mat result of $mat diverged: list at " +
                            "`${segment.key.responseKey}` has ${list.size} items, expected index $index"
                    )
                }
                source = list[index]
            }
            if (source == null) return null
            expectedType = segment.type
        }
        val eod = requireMaterializedNotNull(source as? EngineObjectData) {
            "mat result of $mat diverged: expected object, " +
                "found ${source?.let { it::class.simpleName }}"
        }
        requireMaterializedType(eod, expectedType)
        return eod
    }

    private fun requireMaterializedType(
        source: EngineObjectData,
        expectedType: GraphQLObjectType,
    ) = requireMaterializedType(source.type, expectedType)

    private fun requireMaterializedType(
        actualType: GraphQLObjectType,
        expectedType: GraphQLObjectType,
    ) {
        if (actualType.name != expectedType.name) {
            throw materializationException(
                "mat result of $mat diverged: expected object of type " +
                    "`${expectedType.name}`, found `${actualType.name}`"
            )
        }
    }

    /**
     * Returns the selection subtree available at a member path, unioned across mat results.
     *
     * @param path is the path to the object whose available selections should be returned.
     */
    override fun subtreeAt(path: MatPath): KeyTree = state.coverage.subtreeAt(path)

    private data class State(
        val results: List<MatResult> = emptyList(),
        val coverage: KeyTree = KeyTree.empty,
        val rootType: GraphQLObjectType? = null,
        val pendings: List<Pending> = emptyList(),
    ) {
        fun record(result: MatResult): State =
            copy(
                results = results + result,
                coverage = coverage + result.coverage,
                rootType = rootType ?: result.source.getOrNull()?.type,
            )

        fun remove(pending: Pending): State = copy(pendings = pendings.filterNot { it === pending })

        fun add(pending: Pending): State = copy(pendings = pendings + pending)
    }

    private class Pending(
        val requested: KeyTree,
        val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private sealed interface CoverageAction {
        data object Complete : CoverageAction

        data class Await(val promise: CompletableDeferred<Unit>) : CoverageAction

        data class Invoke(val pending: Pending) : CoverageAction
    }
}
