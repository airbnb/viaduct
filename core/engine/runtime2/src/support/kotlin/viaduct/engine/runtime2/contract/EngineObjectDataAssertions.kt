package viaduct.engine.runtime2.contract

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.outputValue

/** Snapshots this EOD for structural contract assertions. */
internal fun EngineObjectData.Sync.selectionValues(): Map<String, EngineOutputData?> = getSelections().associateWith(::outputValue)
