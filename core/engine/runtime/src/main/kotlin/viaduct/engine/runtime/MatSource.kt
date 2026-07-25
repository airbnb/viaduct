package viaduct.engine.runtime

import viaduct.engine.runtime.mat.KeyTreeFilter
import viaduct.engine.runtime.mat.Mat
import viaduct.engine.runtime.mat.MatLedger
import viaduct.engine.runtime.mat.MatPath.Segment

/**
 * A [MatSource] models how a [Mat]-backed object can materialize missing selections.
 *
 * Most objects have no backing: everything outside [Mat]-backed subtrees carry null on the
 * OER's [MatSource], allocating no [MatLedger] and engaging no read-through machinery.
 * A backed object may be materialized by a [Mat] whose result is selection-sensitive, or by a [Mat]
 * whose result can be reused across requested selection shapes.
 */
sealed interface MatSource {
    /**
     * A source backed by a [MatLedger].
     *
     * @param ledger is the ledger that can read missing fields for this object.
     * @param matFilter projects query-plan shapes to fields owned by the source's [Mat].
     * @param rootNodeId is the intrinsic id when the source is backed by a node reference.
     */
    data class Ledger(
        val ledger: MatLedger,
        val matFilter: KeyTreeFilter = KeyTreeFilter.KeepAll,
        val rootNodeId: String? = null,
    ) : MatSource

    /**
     * An embedded value is an object carried inside a backed parent's materialization.
     *
     * It is not resolved by a [viaduct.engine.runtime.mat.Mat] of its own. Missing reads
     * delegate upward.
     *
     * @param parent is the parent object that owns the backing source.
     * @param segment is the field hop from [parent] to this embedded object.
     */
    class Embedded(val parent: ObjectEngineResultImpl, val segment: Segment) : MatSource
}
