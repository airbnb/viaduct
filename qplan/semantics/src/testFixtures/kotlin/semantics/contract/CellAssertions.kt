package semantics.contract

import model.EngineResult
import model.EngineResultCell

/** Returns the completed value slot for concise result-shape assertions. */
internal fun EngineResultCell.get(): EngineResult? = value.get()
