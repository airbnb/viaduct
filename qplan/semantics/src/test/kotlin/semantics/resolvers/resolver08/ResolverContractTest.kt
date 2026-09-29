package semantics.resolvers.resolver08

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.DepthFirstQueryFringeOrderingContract
import semantics.contract.DepthFirstRootFieldReferenceOrderingContract
import semantics.contract.DepthFirstTaskOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.FrozenObjectResolutionContract
import semantics.contract.NodeResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentResolverContract
import semantics.contract.ObjectFragmentRootFieldReferenceResolverContract
import semantics.contract.QueryFragmentResolverContract
import semantics.contract.QueryFragmentRootFieldReferenceResolverContract
import semantics.contract.ResolverInputInclusionContract
import semantics.contract.ResolverTaskObservation
import semantics.contract.RootFieldReferenceResolverContract
import semantics.contract.SelectiveObjectFragmentOutputPolicyContract
import semantics.contract.SelectiveResolverOutputPolicyContract
import semantics.contract.SelectiveRootFieldReferenceResolverContract
import semantics.contract.SometimesPassiveObjectFragmentResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.contract.SometimesPassiveSelectiveResolverContract
import semantics.resolvers.resolver06.toContractObservation
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    ObjectFragmentRootFieldReferenceResolverContract,
    DepthFirstRootFieldReferenceOrderingContract,
    QueryFragmentRootFieldReferenceResolverContract,
    SelectiveRootFieldReferenceResolverContract,
    ObjectFragmentResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    QueryFragmentResolverContract,
    ResolverInputInclusionContract,
    SometimesPassiveResolverContract,
    SometimesPassiveObjectFragmentResolverContract,
    SometimesPassiveSelectiveResolverContract,
    SelectiveResolverOutputPolicyContract,
    SelectiveObjectFragmentOutputPolicyContract,
    DepthFirstTaskOrderingContract,
    DepthFirstQueryFringeOrderingContract,
    FrozenObjectResolutionContract,
    CorrectResolutionPostTestPolicy {
    override val usesSingularQueryOER: Boolean = true

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)

    override fun resolveAndObserveTasks(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): ObjectEngineResult =
        operation.resolve(
            selections = selections,
            onTaskStarted = { task ->
                taskObserver(task.toContractObservation())
            },
        )
}
