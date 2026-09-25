package semantics.resolvers.resolver23

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.ResolverSelectiveDemandWitnessContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

class ResolverSelectiveDemandWitnessTest : ResolverSelectiveDemandWitnessContract {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
