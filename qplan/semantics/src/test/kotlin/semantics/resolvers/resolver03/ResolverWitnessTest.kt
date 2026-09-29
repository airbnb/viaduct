package semantics.resolvers.resolver03

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.ResolverWitnessContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

class ResolverWitnessTest : ResolverWitnessContract {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
