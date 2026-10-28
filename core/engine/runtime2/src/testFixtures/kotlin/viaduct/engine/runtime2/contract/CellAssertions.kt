package viaduct.engine.runtime2.contract

import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.EngineResultCell

/** Returns the completed value slot for concise result-shape assertions. */
fun EngineResultCell.get(): EngineResult? = value.get()
