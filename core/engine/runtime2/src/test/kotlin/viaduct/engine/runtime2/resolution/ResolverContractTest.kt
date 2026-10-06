package viaduct.engine.runtime2.resolution

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.EmptyObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.FieldCheckerCorrectResolutionContract
import viaduct.engine.runtime2.contract.FromQueryFieldResolverContract
import viaduct.engine.runtime2.contract.FrozenObjectResolutionContract
import viaduct.engine.runtime2.contract.LateObjectPathDemandResolverContract
import viaduct.engine.runtime2.contract.NodeResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentFromArgumentResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentFromObjectPathResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.ParentFieldResolverContract
import viaduct.engine.runtime2.contract.ParentQueryFragmentVariableResolverContract
import viaduct.engine.runtime2.contract.ProductionDeadlockResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentFromObjectPathResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.RootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.SelectiveObjectFragmentOutputPolicyContract
import viaduct.engine.runtime2.contract.SelectiveResolverOutputPolicyContract
import viaduct.engine.runtime2.contract.SelectiveRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveObjectPathResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveSelectiveResolverContract
import viaduct.engine.runtime2.contract.VariableSelectionIdentityResolverContract
import viaduct.engine.runtime2.contract.VariablesProviderResolverContract
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
    ParentQueryFragmentVariableResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    ObjectFragmentFromObjectPathResolverContract,
    QueryFragmentResolverContract,
    QueryFragmentFromObjectPathResolverContract,
    FromQueryFieldResolverContract,
    SometimesPassiveResolverContract,
    SometimesPassiveObjectFragmentResolverContract,
    SometimesPassiveObjectPathResolverContract,
    SometimesPassiveSelectiveResolverContract,
    ProductionDeadlockResolverContract,
    VariableSelectionIdentityResolverContract,
    VariablesProviderResolverContract,
    LateObjectPathDemandResolverContract,
    SelectiveResolverOutputPolicyContract,
    SelectiveObjectFragmentOutputPolicyContract,
    FieldCheckerCorrectResolutionContract,
    CorrectResolutionPostTestPolicy,
    ResolutionDispatcherResource {
    override val usesSingularQueryOER: Boolean = true
    override val usesSingularQueryOERForParentDemand: Boolean = true
    override val singularQueryExpansionDepth: Int = 27

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
