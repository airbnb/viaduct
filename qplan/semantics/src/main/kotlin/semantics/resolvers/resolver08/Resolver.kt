package semantics.resolvers.resolver08

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver01.DepthFirstTask
import semantics.resolvers.resolver06.DepthFirstReactor
import semantics.resolvers.successorDemand
import semantics.shared.SharedOperationContext

/**
 * Resolves [selections] through a depth-first work queue with selective resolver applications.
 * Whether the results contain only the necessary OER nodes has not been proved.
 *
 * Precondition: `world.schema` has no `@parent` fields.
 */
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult = resolve(selections, onTaskStarted = {})

internal fun SharedOperationContext<*>.resolve(
    selections: SelectionForest,
    onTaskStarted: (DepthFirstTask) -> Unit,
): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver08 requires selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        operation = this@resolve,
        complete = { completedSelections ->
            completedSelections.successorDemand(this@resolve)
        },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
