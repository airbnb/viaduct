package viaduct.engine.runtime2.resolvers.resolver03

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.DeepResolverStressContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverStressTest : DeepResolverStressContract {
    override val resolverName: String = "resolver03"

    override val queryFragmentCoverageRequired: Boolean = true

    override val minimumDemandedQueryOERDepth: Int = 2

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
