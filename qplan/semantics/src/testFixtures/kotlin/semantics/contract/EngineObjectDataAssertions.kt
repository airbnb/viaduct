package semantics.contract

import model.EngineOutputData
import model.outputValue
import viaduct.engine.api.EngineObjectData

/** Snapshots this EOD for structural contract assertions. */
internal fun EngineObjectData.Sync.selectionValues(): Map<String, EngineOutputData?> = getSelections().associateWith(::outputValue)
