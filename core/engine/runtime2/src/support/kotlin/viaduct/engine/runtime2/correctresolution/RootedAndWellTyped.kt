package viaduct.engine.runtime2.correctresolution

import viaduct.engine.runtime2.model.Assumptions
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireQueryTypeDef

/**
 * Whether this result is rooted at the reasoning world's canonical Query type.
 *
 * The [ObjectEngineResult] receiver already establishes that the result is object-valued.
 */
fun ObjectEngineResult.rootedAndWellTyped(world: Assumptions): Boolean = type == world.schema.requireQueryTypeDef()
