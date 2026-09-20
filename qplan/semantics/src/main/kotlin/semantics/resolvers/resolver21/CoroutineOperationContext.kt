package semantics.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import model.SelectionForest
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.resolver26.CoroutineTaskDispatcher
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext

/** One Resolver21-23 execution scope: shared state, grounded-demand policy, scheduling, and cycle checking. */
internal class CoroutineOperationContext(
    base: SharedOperationContext<*>,
    requestScope: CoroutineScope,
    val complete: (SelectionForest) -> SelectionForest,
    val cycleChecker: CycleCheckState,
) : SharedOperationContext<
    CoroutineTaskDispatcher<
        CoroutineOrchestrationTask,
        GroundedFieldPublicationOccurrence<CoroutineOperationContext>,
        GroundedFieldCheckerPublicationOccurrence,
    >,
> by
    SharedOperationContext.create(
        world = base.world,
        variableBindings = base.variableBindings,
        resolverObserver = base.resolverObserver,
        dispatcher = CoroutineTaskDispatcher<
            CoroutineOrchestrationTask,
            GroundedFieldPublicationOccurrence<CoroutineOperationContext>,
            GroundedFieldCheckerPublicationOccurrence,
        >(
            requestScope = requestScope,
            runFieldResolver = CoroutineFieldResolverTask::execute,
            runFieldChecker = CoroutineFieldCheckerTask::execute,
        ),
    ) {
    val passiveValues = CoroutinePassiveValueResolutionLogic(this)
}
