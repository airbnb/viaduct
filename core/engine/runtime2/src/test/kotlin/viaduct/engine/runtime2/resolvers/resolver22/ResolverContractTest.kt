package viaduct.engine.runtime2.resolvers.resolver22

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CompleteObjectFragmentOutputPolicyContract
import viaduct.engine.runtime2.contract.CompleteOutputRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.CompleteResolverOutputPolicyContract
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.EmptyObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.FrozenObjectResolutionContract
import viaduct.engine.runtime2.contract.NodeResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentFromArgumentResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.ParentFieldResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.ResolverInputInclusionContract
import viaduct.engine.runtime2.contract.RootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
    ResolverInputInclusionContract,
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
    ): ObjectEngineResult = operation.resolve(selections)
}
