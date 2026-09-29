package semantics.resolver26

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.FieldCheckerCorrectResolutionContract
import semantics.contract.FromQueryFieldResolverContract
import semantics.contract.FrozenObjectResolutionContract
import semantics.contract.LateObjectPathDemandResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentFromObjectPathResolverContract
import semantics.contract.ObjectFragmentResolverContract
import semantics.contract.ObjectFragmentRootFieldReferenceResolverContract
import semantics.contract.ParentFieldResolverContract
import semantics.contract.ParentQueryFragmentVariableResolverContract
import semantics.contract.ProductionDeadlockResolverContract
import semantics.contract.QueryFragmentFromObjectPathResolverContract
import semantics.contract.QueryFragmentResolverContract
import semantics.contract.QueryFragmentRootFieldReferenceResolverContract
import semantics.contract.RootFieldReferenceResolverContract
import semantics.contract.SelectiveObjectFragmentOutputPolicyContract
import semantics.contract.SelectiveResolverOutputPolicyContract
import semantics.contract.SelectiveRootFieldReferenceResolverContract
import semantics.contract.SometimesPassiveObjectFragmentResolverContract
import semantics.contract.SometimesPassiveObjectPathResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.contract.SometimesPassiveSelectiveResolverContract
import semantics.contract.VariableSelectionIdentityResolverContract
import semantics.contract.VariablesProviderResolverContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

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
    Resolver26DispatcherResource {
    override val usesSingularQueryOER: Boolean = true
    override val usesSingularQueryOERForParentDemand: Boolean = true
    override val singularQueryExpansionDepth: Int = 27

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
