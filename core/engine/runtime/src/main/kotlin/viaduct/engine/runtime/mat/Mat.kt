package viaduct.engine.runtime.mat

import viaduct.engine.api.EngineExecutionContext

/** Materializes object data for a requested selection shape. */
fun interface Mat {
    /**
     * Materializes a requested selection shape.
     *
     * The returned [MatResult] is expected to completely describe the selections supplied by this
     * materialization, including any surplus selections that were materialized even if they weren't
     * in [keyTree]. Engine-managed fields are excluded from materialization coverage. The result
     * source may also record a materialization failure for the covered shape.
     *
     * @param keyTree is the selection shape to materialize.
     * @param selectionHandle the execution scope for the selection being materialized
     */
    suspend operator fun invoke(
        keyTree: KeyTree,
        selectionHandle: EngineExecutionContext.ExecutionHandle,
    ): MatResult

    companion object {
        /** A Mat that always returns a null result */
        val Null: Mat = Mat { tree, _ -> MatResult(tree, Result.success(null)) }
    }
}
