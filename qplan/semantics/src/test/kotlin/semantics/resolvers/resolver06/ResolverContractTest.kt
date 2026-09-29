package semantics.resolvers.resolver06

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CompleteOutputRootFieldReferenceResolverContract
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.DepthFirstRootFieldReferenceOrderingContract
import semantics.contract.DepthFirstTaskOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.FrozenObjectResolutionContract
import semantics.contract.NodeResolverContract
import semantics.contract.ResolverTaskObservation
import semantics.contract.RootFieldReferenceResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    CompleteOutputRootFieldReferenceResolverContract,
    DepthFirstRootFieldReferenceOrderingContract,
    SometimesPassiveResolverContract,
    CompleteResolverOutputPolicyContract,
    DepthFirstTaskOrderingContract,
    FrozenObjectResolutionContract,
    CorrectResolutionPostTestPolicy {
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
