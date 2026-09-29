package semantics.resolvers.resolver07

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver01.DepthFirstTask
import semantics.resolvers.resolver06.DepthFirstReactor
import semantics.resolvers.successorBoundaryDemand
import semantics.shared.SharedOperationContext

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
