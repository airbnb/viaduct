package viaduct.engine.runtime2.resolvers.resolver08

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.DepthFirstQueryFringeOrderingContract
import viaduct.engine.runtime2.contract.DepthFirstRootFieldReferenceOrderingContract
import viaduct.engine.runtime2.contract.DepthFirstTaskOrderingContract
import viaduct.engine.runtime2.contract.EmptyObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.FrozenObjectResolutionContract
import viaduct.engine.runtime2.contract.NodeResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentFromArgumentResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.ResolverInputInclusionContract
import viaduct.engine.runtime2.contract.ResolverTaskObservation
import viaduct.engine.runtime2.contract.RootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.SelectiveObjectFragmentOutputPolicyContract
import viaduct.engine.runtime2.contract.SelectiveResolverOutputPolicyContract
import viaduct.engine.runtime2.contract.SelectiveRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveSelectiveResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver06.toContractObservation

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
