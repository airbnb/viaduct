package semantics.resolvers.resolver02

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CompleteObjectFragmentOutputPolicyContract
import semantics.contract.CompleteOutputRootFieldReferenceResolverContract
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.DepthFirstQueryFringeOrderingContract
import semantics.contract.DepthFirstRootFieldReferenceOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.FrozenObjectResolutionContract
import semantics.contract.NodeResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentResolverContract
import semantics.contract.ObjectFragmentRootFieldReferenceResolverContract
import semantics.contract.QueryFragmentResolverContract
import semantics.contract.QueryFragmentRootFieldReferenceResolverContract
import semantics.contract.ResolverInputInclusionContract
import semantics.contract.RootFieldReferenceResolverContract
import semantics.contract.SometimesPassiveObjectFragmentResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    FrozenObjectResolutionContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    ObjectFragmentRootFieldReferenceResolverContract,
    CompleteOutputRootFieldReferenceResolverContract,
    DepthFirstRootFieldReferenceOrderingContract,
    DepthFirstQueryFringeOrderingContract,
    QueryFragmentRootFieldReferenceResolverContract,
    ObjectFragmentResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    QueryFragmentResolverContract,
    ResolverInputInclusionContract,
    SometimesPassiveResolverContract,
    SometimesPassiveObjectFragmentResolverContract,
    CompleteResolverOutputPolicyContract,
    CompleteObjectFragmentOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override val usesSingularQueryOER: Boolean = true

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
