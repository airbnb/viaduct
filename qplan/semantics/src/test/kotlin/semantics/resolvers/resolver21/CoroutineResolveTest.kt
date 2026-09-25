package semantics.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.FragmentFreeFieldCheckerPublicationContract
import semantics.contract.CoroutineResolverContract
import semantics.contract.CoroutineResolverTestSubject
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext

class CoroutineResolveTest :
    CoroutineResolverTestSubject(),
    CoroutineResolverContract,
    FragmentFreeFieldCheckerPublicationContract {
    override val selectiveResolvers = false
    override val usesSingularQueryOER = true

    override fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult = startCoroutineResolution(operation, requestScope, selections, cycleChecker)
}

internal fun startCoroutineResolution(
    operation: SharedOperationContext<*>,
    requestScope: CoroutineScope,
    selections: SelectionForest,
    cycleChecker: CycleCheckState,
    complete: (SelectionForest) -> SelectionForest = { it },
    supportsCheckerFragments: Boolean = false,
): ObjectEngineResult =
    CoroutineOperationContext(
        operation,
        requestScope,
        complete,
        cycleChecker,
        supportsCheckerFragments,
    ).startResolve(
        source = operation.world.resolverRegistry.createRootQueryInput(),
        demand = semantics.shared.Demand.checked(selections),
    )
