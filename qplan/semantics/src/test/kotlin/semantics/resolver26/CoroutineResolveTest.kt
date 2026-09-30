package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.SelectionForest
import model.schemaType
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
import semantics.contract.SelectiveFieldCheckerExactnessContract
import semantics.contract.SelectiveTypeCheckerExactnessContract
import semantics.shared.CycleCheckState
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext

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
        val root = OrchestrationTask.createObjectResult(resolverOperation, source.schemaType, semantics.shared.Demand.checked(selections))
        resolverOperation.dispatcher.dispatchOrchestration(
            OrchestrationTask.create(resolverOperation, OEROccurrence(root, emptyList(), root), source, selections),
        )
        return root
    }
}
