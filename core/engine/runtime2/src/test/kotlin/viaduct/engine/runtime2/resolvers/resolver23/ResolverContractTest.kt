package viaduct.engine.runtime2.resolvers.resolver23

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.EmptyObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.FieldCheckerCorrectResolutionContract
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
import viaduct.engine.runtime2.contract.SelectiveObjectFragmentOutputPolicyContract
import viaduct.engine.runtime2.contract.SelectiveResolverOutputPolicyContract
import viaduct.engine.runtime2.contract.SelectiveRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveSelectiveResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverContractTest :
    FrozenObjectResolutionContract,
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    ObjectFragmentRootFieldReferenceResolverContract,
    QueryFragmentRootFieldReferenceResolverContract,
    SelectiveRootFieldReferenceResolverContract,
    ObjectFragmentResolverContract,
    ParentFieldResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    QueryFragmentResolverContract,
    ResolverInputInclusionContract,
    SometimesPassiveResolverContract,
    SometimesPassiveObjectFragmentResolverContract,
    SometimesPassiveSelectiveResolverContract,
    SelectiveResolverOutputPolicyContract,
    SelectiveObjectFragmentOutputPolicyContract,
    FieldCheckerCorrectResolutionContract,
    CorrectResolutionPostTestPolicy {
    override val usesSingularQueryOER: Boolean = true
    override val usesSingularQueryOERForParentDemand: Boolean = true
    override val singularQueryExpansionDepth: Int = 27

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
