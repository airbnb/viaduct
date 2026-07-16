package viaduct.engine.runtime.mat

import viaduct.engine.api.EngineObjectData

/**
 * One materialization result recorded in a ledger.
 *
 * @param coverage is the selection shape covered by this result.
 * @param source is the object data returned by materialization. A successful null value means
 *   the result resolved to null, while a failed value means materialization failed for [coverage].
 */
class MatResult(
    val coverage: KeyTree,
    val source: Result<EngineObjectData?>,
)
