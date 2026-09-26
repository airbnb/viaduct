package semantics.shared

import model.ObjectSelectionForest
import viaduct.engine.api.EngineObjectData

/** The occurrence, passive source, and closed construction demand for one OER. */
class SharedOERContext internal constructor(
    val occurrence: OEROccurrence,
    val source: EngineObjectData.Sync,
    val closedDemand: ObjectSelectionForest,
)
