package viaduct.engine.runtime2.resolution

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.ResolverContract
import viaduct.engine.runtime2.contract.ResolverStressSuite
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.RESOLUTION_STRESS_CONFIG_OVERRIDES
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

@Tag("BazelIgnore")
class ResolverStressTest : ResolverContract, ResolutionDispatcherResource {
    @Test
    fun `deep dependency-heavy arbitrary worlds resolve correctly`(): Unit =
        ResolverStressSuite(
            subject = this,
            resolverName = "resolution",
            objectPathVariablesEnabled = true,
            queryPathVariablesEnabled = true,
            sometimesPassiveCoverageRequired = true,
            rootFieldReferenceCoverageRequired = true,
            stressConfigOverrides = RESOLUTION_STRESS_CONFIG_OVERRIDES,
        ).check()

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
