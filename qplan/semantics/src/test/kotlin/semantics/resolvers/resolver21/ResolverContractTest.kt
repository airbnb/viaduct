package semantics.resolvers.resolver21

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CompleteOutputRootFieldReferenceResolverContract
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.FrozenObjectResolutionContract
import semantics.contract.NodeResolverContract
import semantics.contract.RootFieldReferenceResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

class ResolverContractTest :
    FrozenObjectResolutionContract,
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    CompleteOutputRootFieldReferenceResolverContract,
    SometimesPassiveResolverContract,
    CompleteResolverOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
