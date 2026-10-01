package viaduct.engine.runtime2.resolvers.resolver23

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ParentFieldsEnabled
import viaduct.engine.runtime2.contract.DeepResolverStressContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
    ): ObjectEngineResult = operation.resolve(selections)
}
