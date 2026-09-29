package semantics.correctresolution

import model.Assumptions
import model.ObjectEngineResult
import model.requireQueryTypeDef

/**
 * Whether this result is rooted at the reasoning world's canonical Query type.
 *
 * The [ObjectEngineResult] receiver already establishes that the result is object-valued.
 */
fun ObjectEngineResult.rootedAndWellTyped(world: Assumptions): Boolean = type == world.schema.requireQueryTypeDef()
