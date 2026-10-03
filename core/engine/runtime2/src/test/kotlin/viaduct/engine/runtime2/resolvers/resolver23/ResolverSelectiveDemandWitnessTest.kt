package viaduct.engine.runtime2.resolvers.resolver23

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.ResolverSelectiveDemandWitnessContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverSelectiveDemandWitnessTest : ResolverSelectiveDemandWitnessContract {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
