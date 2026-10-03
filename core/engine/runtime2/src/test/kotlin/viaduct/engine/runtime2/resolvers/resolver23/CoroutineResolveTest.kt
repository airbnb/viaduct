package viaduct.engine.runtime2.resolvers.resolver23

import kotlinx.coroutines.CoroutineScope
import viaduct.engine.runtime2.contract.CoroutineResolverContract
import viaduct.engine.runtime2.contract.CoroutineResolverTestSubject
import viaduct.engine.runtime2.contract.FragmentFreeCheckerProfileContract
import viaduct.engine.runtime2.contract.FragmentFreeFieldCheckerEnforcementContract
import viaduct.engine.runtime2.contract.FragmentFreeFieldCheckerPublicationContract
import viaduct.engine.runtime2.contract.FragmentFreeTypeCheckerEnforcementContract
import viaduct.engine.runtime2.contract.GroundedFieldCheckerCapabilityContract
import viaduct.engine.runtime2.contract.GroundedFieldCheckerObjectFragmentContract
import viaduct.engine.runtime2.contract.GroundedFieldCheckerQueryFragmentContract
import viaduct.engine.runtime2.contract.GroundedTypeCheckerFragmentContract
import viaduct.engine.runtime2.contract.GroundedTypeCheckerLifecycleContract
import viaduct.engine.runtime2.contract.SelectiveFieldCheckerExactnessContract
import viaduct.engine.runtime2.contract.SelectiveTypeCheckerExactnessContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver21.startCoroutineResolution
import viaduct.engine.runtime2.resolvers.successorDemandFromConstructionDemand

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
    GroundedTypeCheckerFragmentContract,
    SelectiveFieldCheckerExactnessContract,
    SelectiveTypeCheckerExactnessContract {
    override val usesSingularQueryOER = true

    override val selectiveResolvers = true

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
            complete = { demand, possibleRootTypes ->
                demand.successorDemandFromConstructionDemand(operation, possibleRootTypes)
            },
        )
}
