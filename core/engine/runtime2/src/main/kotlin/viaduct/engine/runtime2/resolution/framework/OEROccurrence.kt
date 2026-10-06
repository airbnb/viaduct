package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent

/** Stable identity and location of one object-result occurrence. */
class OEROccurrence(
    val root: ObjectEngineResult,
    path: List<PathComponent>,
    val target: ObjectEngineResult,
    val parent: OEROccurrence? = null,
) {
    val path: List<PathComponent> = path.toList()

    fun coordinate(key: ObjectEngineResult.ObjectKey): List<PathComponent> = path + key
}
