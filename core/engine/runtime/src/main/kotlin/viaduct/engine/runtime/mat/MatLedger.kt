package viaduct.engine.runtime.mat

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime.result.ObjectEngineResult

/**
 * Tracks materialization results for an object.
 *
 * A MatLedger is rooted at one object. Callers use [MatPath] to read fields on that object or on
 * objects nested under it. The ledger hides which materialization result supplied each field.
 */
interface MatLedger {
    /** Ensures that [requested] is materialized. */
    suspend fun ensureCoverage(
        requested: KeyTree,
        selectionHandle: EngineExecutionContext.ExecutionHandle,
    )

    /**
     * Finds the object to read [key] from.
     *
     * The ledger can hold several results for its root object; this uses one that includes [key].
     * Throws if that result failed.
     *
     * @param path is the path from the ledger's root object to the object that holds [key].
     * @param key is the field to read.
     */
    suspend fun resolveSource(
        path: MatPath,
        key: ObjectEngineResult.Key,
    ): Source

    /** The result of [resolveSource]: the object to read from, or why there isn't one. */
    sealed interface Source {
        /** [data] is the object to read from, or null when an object on the path is null. */
        data class Resolved(val data: EngineObjectData?) : Source

        /** A field on the path is missing from the result, as opposed to being null. */
        data object Missing : Source
    }

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
