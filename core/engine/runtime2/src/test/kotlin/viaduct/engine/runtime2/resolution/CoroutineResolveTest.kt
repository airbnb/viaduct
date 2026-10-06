package viaduct.engine.runtime2.resolution

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
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class CoroutineResolveTest :
    CoroutineResolverTestSubject(),
    CoroutineResolverContract,
    FragmentFreeCheckerProfileContract,
    FragmentFreeFieldCheckerPublicationContract,
    FragmentFreeFieldCheckerEnforcementContract,
    GroundedFieldCheckerCapabilityContract,
    GroundedFieldCheckerObjectFragmentContract,
    GroundedFieldCheckerQueryFragmentContract,
    SelectiveFieldCheckerExactnessContract,
    FragmentFreeTypeCheckerEnforcementContract,
    GroundedTypeCheckerFragmentContract,
    GroundedTypeCheckerLifecycleContract,
    SelectiveTypeCheckerExactnessContract {
    override val usesSingularQueryOER = true
    override val coalescesGroundedKeys = false

    override fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult {
        val resolverOperation = OperationContext.create(
            operation,
            requestScope,
            cycleChecker,
        )
        val source = operation.world.resolverRegistry.createRootQueryInput()
        val root = OrchestrationTask.createObjectResult(resolverOperation, source.schemaType, viaduct.engine.runtime2.resolution.framework.Demand.checked(selections))
        resolverOperation.dispatcher.dispatchOrchestration(
            OrchestrationTask.create(resolverOperation, OEROccurrence(root, emptyList(), root), source, selections),
        )
        return root
    }
}
