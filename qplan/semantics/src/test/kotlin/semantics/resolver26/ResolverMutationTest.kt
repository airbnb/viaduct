package semantics.resolver26

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.ResolverMutationContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

class ResolverMutationTest : ResolverMutationContract, Resolver26DispatcherResource {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
