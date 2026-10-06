package viaduct.engine.runtime2.resolvers.resolver02

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstResolve
import viaduct.engine.runtime2.resolvers.successorBoundaryDemand

/**
 * Resolves [selections] with non-selective resolver applications. Results may contain more OER
 * nodes than are strictly necessary to resolve the query.
 *
 * Precondition: `world.schema` has no `@parent` fields.
 */
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver02 requires non-selective resolvers"
    }
    return DepthFirstResolve(
        operation = this@resolve,
        complete = { completedSelections ->
            completedSelections.successorBoundaryDemand(this@resolve)
        },
    ).resolve(selections)
}
