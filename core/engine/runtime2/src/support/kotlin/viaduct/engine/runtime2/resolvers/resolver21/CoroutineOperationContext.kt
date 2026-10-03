package viaduct.engine.runtime2.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.CoroutineTaskDispatcher
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

/** One Resolver21-23 execution scope: shared state, grounded-demand policy, scheduling, and cycle checking. */
internal class CoroutineOperationContext(
    base: SharedOperationContext<*>,
    requestScope: CoroutineScope,
    val complete: (Demand<SelectionForest>, Set<ViaductSchema.Object>) -> SelectionForest,
    val cycleChecker: CycleCheckState,
) : SharedOperationContext<CoroutineTaskDispatcher> by
    SharedOperationContext.create(
        world = base.world,
        variableBindings = base.variableBindings,
        resolverObserver = base.resolverObserver,
        checkerObserver = base.checkerObserver,
        dispatcher = CoroutineTaskDispatcher(
            requestScope = requestScope,
        ),
    ) {
    val passiveValues = CoroutinePassiveValueResolutionLogic(this)
}
