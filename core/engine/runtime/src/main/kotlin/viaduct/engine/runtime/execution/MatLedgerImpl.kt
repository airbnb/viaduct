package viaduct.engine.runtime.execution

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.Mat
import viaduct.engine.runtime.mat.MatLedger
import viaduct.engine.runtime.mat.MatPath
import viaduct.engine.runtime.mat.MatResult
import viaduct.engine.runtime.mat.subtreeAt

/**
 * Stores the results produced by one [Mat] and reads fields from those results.
 *
 * @param mat The ledger calls this value when it needs to load missing fields.
 */
internal class MatLedgerImpl(
    private val mat: Mat,
) : MatLedger {
    /**
     * Guards the ledger's materialization state.
     *
     * Only one caller may invoke [mat] and record a result in this ledger.
     * Other callers suspend while they wait for this mutex.
     */
    private val mutex = Mutex()

    @Volatile
    private var results: List<MatResult> = emptyList()

    @Volatile
    private var coverage: KeyTree = KeyTree.empty

    @Volatile
    private var rootType: GraphQLObjectType? = null

    /**
     * Records the result available when this ledger is created.
     *
     * Initialization is optional, may happen at most once, and must precede materialization.
     */
    suspend fun initialize(result: MatResult) {
        mutex.withLock {
            check(results.isEmpty()) { "Mat ledger for $mat is already initialized" }
            recordResultUnsafe(result)
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
    ): MatResult =
        mutex.withLock {
            check(results.isEmpty()) { "Mat ledger for $mat is already initialized" }
            invokeAndRecordUnsafe(requested, selectionHandle)
        }

    /** Records a result after the caller has locked [mutex]. */
    private fun recordResultUnsafe(result: MatResult) {
        if (rootType == null) {
            rootType = result.source.getOrNull()?.type
        }
        results = results + result
        coverage += result.coverage
    }

    /** Invokes [mat] and records its result after the caller has locked [mutex]. */
    private suspend fun invokeAndRecordUnsafe(
        requested: KeyTree,
        selectionHandle: EngineExecutionContext.ExecutionHandle,
    ): MatResult {
        val result = mat(requested, selectionHandle)
        recordResultUnsafe(result)
        return result
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
        mutex.withLock {
            val missing = requested - coverage

            if (missing.isEmpty()) return@withLock

            invokeAndRecordUnsafe(missing, selectionHandle)
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
        val expectedRootType = rootType ?: path.rootType
        requireMaterializedType(path.rootType, expectedRootType)

        val matResult = requireMaterializedNotNull(
            results.firstOrNull { result ->
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
    override fun subtreeAt(path: MatPath): KeyTree = coverage.subtreeAt(path)
}
