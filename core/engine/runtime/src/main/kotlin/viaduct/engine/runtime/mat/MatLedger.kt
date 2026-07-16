package viaduct.engine.runtime.mat

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime.ObjectEngineResult

/**
 * Tracks materialization results for an object.
 *
 * A MatLedger is rooted at one object. Callers use [MatPath] to read fields on that object or on
 * objects nested under it. The ledger hides which materialization result supplied each field.
 */
interface MatLedger {
    /** Adds [result] to this ledger. */
    suspend fun appendResult(result: MatResult)

    /** Ensures that [requested] is materialized. */
    suspend fun ensureCoverage(
        requested: KeyTree,
        selectionHandle: EngineExecutionContext.ExecutionHandle,
    )

    /**
     * Resolves the backing source object at the provided path for a covered field.
     *
     * A null result means the covered materialization resolved successfully to null. Failed
     * materialization results throw when read.
     *
     * @param path is the path to the object being read.
     * @param key is the terminal field instance that must be covered by the resolved source.
     */
    suspend fun resolveSource(
        path: MatPath,
        key: ObjectEngineResult.Key,
    ): EngineObjectData?

    /**
     * Returns the selection subtree that is available at a path.
     *
     * The returned tree starts at the object named by [path]. It is the union of the matching
     * subtrees from recorded Mat results.
     *
     * @param path is the path from the directly backed object to the object whose selections are
     * being requested.
     */
    fun subtreeAt(path: MatPath): KeyTree
}
