package viaduct.engine.runtime2.resolvers.resolver01

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CompleteOutputRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.CompleteResolverOutputPolicyContract
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.DepthFirstRootFieldReferenceOrderingContract
import viaduct.engine.runtime2.contract.EmptyObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.FrozenObjectResolutionContract
import viaduct.engine.runtime2.contract.NodeResolverContract
import viaduct.engine.runtime2.contract.RootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    FrozenObjectResolutionContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    CompleteOutputRootFieldReferenceResolverContract,
    DepthFirstRootFieldReferenceOrderingContract,
    SometimesPassiveResolverContract,
    CompleteResolverOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
