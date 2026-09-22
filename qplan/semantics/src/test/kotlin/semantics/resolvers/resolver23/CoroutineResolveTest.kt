package semantics.resolvers.resolver23

import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.FragmentFreeFieldCheckerPublicationContract
import semantics.contract.FragmentFreeFieldCheckerEnforcementContract
import semantics.contract.CoroutineResolverContract
import semantics.contract.CoroutineResolverTestSubject
import semantics.resolvers.resolver21.startCoroutineResolution
import semantics.resolvers.successorDemand
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext

class CoroutineResolveTest :
    CoroutineResolverTestSubject(),
    CoroutineResolverContract,
    FragmentFreeFieldCheckerPublicationContract,
    FragmentFreeFieldCheckerEnforcementContract {
    override val usesSingularQueryOER = true

    override val selectiveResolvers = true

    override fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult = startCoroutineResolution(
        operation, requestScope, selections, cycleChecker,
        complete = { demand -> demand.successorDemand(operation) },
    )
}
