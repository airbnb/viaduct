package semantics.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import model.SelectionForest
import semantics.resolver26.CoroutineTaskDispatcher
import semantics.shared.CycleCheckState
import semantics.shared.Demand
import semantics.shared.SharedOperationContext
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
