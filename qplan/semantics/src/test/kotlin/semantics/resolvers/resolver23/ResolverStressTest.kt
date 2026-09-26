package semantics.resolvers.resolver23

import semantics.resolvers.resolver23.resolve

import viaduct.engine.api.EngineObjectData

import semantics.shared.SharedOperationContext
import model.ObjectEngineResult
import model.SelectionForest
import semantics.arbitrary.Config
import semantics.arbitrary.ParentFieldsEnabled
import semantics.contract.DeepResolverStressContract

class ResolverStressTest : DeepResolverStressContract {
    override val resolverName: String = "resolver23"

    override val queryFragmentCoverageRequired: Boolean = true

    override val stressConfigOverrides: Config =
        Config.default +
            (ParentFieldsEnabled to true)

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        operation.resolve(selections)
}
