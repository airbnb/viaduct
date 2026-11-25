package viaduct.engine.runtime.mat

import viaduct.engine.api.EngineObjectData

/**
 * One materialization result recorded in a ledger.
 *
 * @param coverage is the selection shape covered by this result.
 * @param source is the object data returned by materialization. A successful null value means
 *   the result resolved to null, while a failed value means materialization failed for [coverage].
 * @param launchAfterRecord starts work that reads this result; the ledger calls it once, after
 *   recording the result.
 */
class MatResult(
    val coverage: KeyTree,
    val source: Result<EngineObjectData?>,
    val launchAfterRecord: () -> Unit = {},
)
