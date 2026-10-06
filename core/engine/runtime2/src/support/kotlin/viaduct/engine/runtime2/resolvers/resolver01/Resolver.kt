package viaduct.engine.runtime2.resolvers.resolver01

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/**
 * Resolves [selections] when resolver object fragments are empty. Results are non-selective and
 * may contain more OER nodes than are strictly necessary to resolve the query.
 *
 * Precondition: `world.schema` has no `@parent` fields.
 */
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver01 requires non-selective resolvers"
    }
    return DepthFirstResolve(
        operation = this@resolve,
        complete = { completedSelections -> completedSelections },
    ).resolve(selections)
}
