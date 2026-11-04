package viaduct.engine.runtime2.resolution

import org.junit.jupiter.api.Tag
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.contract.DeepResolverStressContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.RESOLUTION_STRESS_CONFIG_OVERRIDES
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

@Tag("BazelIgnore")
class ResolverStressTest : DeepResolverStressContract, ResolutionDispatcherResource {
    override val resolverName: String = "resolution"

    override val objectPathVariablesEnabled: Boolean = true

    override val queryPathVariablesEnabled: Boolean = true

    override val sometimesPassiveCoverageRequired: Boolean = true

    override val rootFieldReferenceCoverageRequired: Boolean = true

    override val stressConfigOverrides: Config = RESOLUTION_STRESS_CONFIG_OVERRIDES

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
