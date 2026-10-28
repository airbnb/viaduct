package viaduct.engine.runtime2.resolvers.resolver06

import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** One depth-first resolution operation: shared state, demand policy, scheduling, and passive traversal. */
class DepthFirstOperationContext(
    operation: SharedOperationContext<*>,
    val complete: (SelectionForest) -> SelectionForest,
    dispatcher: DepthFirstReactor,
) : SharedOperationContext<DepthFirstReactor> by SharedOperationContext.create(
        world = operation.world,
        variableBindings = operation.variableBindings,
        resolverObserver = operation.resolverObserver,
        checkerObserver = operation.checkerObserver,
        dispatcher = dispatcher,
    ) {
    /** Passive traversal inherits the Query-OER depth of the task whose value it is traversing. */
    fun passiveValues(queryOERDepth: Int) = DepthFirstPassiveValueResolutionLogic(this, queryOERDepth)
}
