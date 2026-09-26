package semantics.resolvers.resolver22

import semantics.resolvers.resolver22.resolve

import viaduct.engine.api.EngineObjectData

import semantics.shared.SharedOperationContext
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CompleteObjectFragmentOutputPolicyContract
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CompleteOutputRootFieldReferenceResolverContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.FrozenObjectResolutionContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentResolverContract
import semantics.contract.ParentFieldResolverContract
import semantics.contract.QueryFragmentResolverContract
import semantics.contract.QueryFragmentRootFieldReferenceResolverContract
import semantics.contract.RootFieldReferenceResolverContract
import semantics.contract.ObjectFragmentRootFieldReferenceResolverContract
import semantics.contract.SometimesPassiveObjectFragmentResolverContract
import semantics.contract.SometimesPassiveResolverContract

class ResolverContractTest :
    FrozenObjectResolutionContract,
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    ObjectFragmentRootFieldReferenceResolverContract,
    CompleteOutputRootFieldReferenceResolverContract,
    QueryFragmentRootFieldReferenceResolverContract,
    ObjectFragmentResolverContract,
    ParentFieldResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    QueryFragmentResolverContract,
    SometimesPassiveResolverContract,
    SometimesPassiveObjectFragmentResolverContract,
    CompleteResolverOutputPolicyContract,
    CompleteObjectFragmentOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override val usesSingularQueryOER: Boolean = true
    override val usesSingularQueryOERForParentDemand: Boolean = true

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        operation.resolve(selections)
}
