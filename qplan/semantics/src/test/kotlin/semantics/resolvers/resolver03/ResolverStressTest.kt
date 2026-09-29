package semantics.resolvers.resolver03

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.DeepResolverStressContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

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
