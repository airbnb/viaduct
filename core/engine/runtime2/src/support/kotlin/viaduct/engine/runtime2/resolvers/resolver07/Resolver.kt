package viaduct.engine.runtime2.resolvers.resolver07

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstTask
import viaduct.engine.runtime2.resolvers.resolver06.DepthFirstReactor
import viaduct.engine.runtime2.resolvers.successorBoundaryDemand

/**
 * Resolves [selections] through a depth-first work queue with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
 *
 * Precondition: `world.schema` has no `@parent` fields.
 */
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult = resolve(selections, onTaskStarted = {})

internal fun SharedOperationContext<*>.resolve(
    selections: SelectionForest,
    onTaskStarted: (DepthFirstTask) -> Unit,
): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver07 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        operation = this@resolve,
        complete = { completedSelections ->
            completedSelections.successorBoundaryDemand(this@resolve)
        },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
