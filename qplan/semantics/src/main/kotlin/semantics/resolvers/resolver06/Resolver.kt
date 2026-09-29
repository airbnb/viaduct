package semantics.resolvers.resolver06

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver01.DepthFirstTask
import semantics.shared.SharedOperationContext

/**
 * Resolves [selections] through a depth-first work queue when resolver object fragments are empty.
 * Results are non-selective and may contain more OER nodes than are strictly necessary to resolve
 * the query.
 *
 * Precondition: `world.schema` has no `@parent` fields.
 */
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult = resolve(selections, onTaskStarted = {})

internal fun SharedOperationContext<*>.resolve(
    selections: SelectionForest,
    onTaskStarted: (DepthFirstTask) -> Unit,
): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver06 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        operation = this@resolve,
        complete = { completedSelections -> completedSelections },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
