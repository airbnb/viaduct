package viaduct.engine.runtime2.resolvers.resolver03

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstResolve
import viaduct.engine.runtime2.resolvers.successorDemand

/**
 * Resolves [selections] with selective resolver applications. Whether the results contain only the
 * necessary OER nodes has not been proved.
 *
 * Precondition: `world.schema` has no `@parent` fields.
 */
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver03 requires selective resolvers"
    }
    return DepthFirstResolve(
        operation = this@resolve,
        complete = { completedSelections ->
            completedSelections.successorDemand(this@resolve)
        },
    ).resolve(selections)
}
