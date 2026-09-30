package semantics.resolvers.resolver22

import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CoroutineResolverContract
import semantics.contract.CoroutineResolverTestSubject
import semantics.contract.FragmentFreeCheckerProfileContract
import semantics.contract.FragmentFreeFieldCheckerEnforcementContract
import semantics.contract.FragmentFreeFieldCheckerPublicationContract
import semantics.contract.FragmentFreeTypeCheckerEnforcementContract
import semantics.contract.GroundedFieldCheckerCapabilityContract
import semantics.contract.GroundedFieldCheckerObjectFragmentContract
import semantics.contract.GroundedFieldCheckerQueryFragmentContract
import semantics.contract.GroundedTypeCheckerFragmentContract
import semantics.contract.GroundedTypeCheckerLifecycleContract
import semantics.resolvers.resolver21.startCoroutineResolution
import semantics.resolvers.successorBoundaryDemand
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext

class CoroutineResolveTest :
    CoroutineResolverTestSubject(),
    CoroutineResolverContract,
    FragmentFreeCheckerProfileContract,
    FragmentFreeFieldCheckerPublicationContract,
    FragmentFreeFieldCheckerEnforcementContract,
    FragmentFreeTypeCheckerEnforcementContract,
    GroundedFieldCheckerCapabilityContract,
    GroundedFieldCheckerObjectFragmentContract,
    GroundedFieldCheckerQueryFragmentContract,
    GroundedTypeCheckerLifecycleContract,
    GroundedTypeCheckerFragmentContract {
    override val selectiveResolvers = false
    override val usesSingularQueryOER = true

    override fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult =
        startCoroutineResolution(
            operation,
            requestScope,
            selections,
            cycleChecker,
            complete = { demand, _ -> demand.values.successorBoundaryDemand(operation) },
        )
}
