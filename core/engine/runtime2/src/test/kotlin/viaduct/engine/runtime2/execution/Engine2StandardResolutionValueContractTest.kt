package viaduct.engine.runtime2.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.runtime.execution.StandardResolutionValueContract

@ExperimentalCoroutinesApi
class Engine2StandardResolutionValueContractTest : StandardResolutionValueContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "StandardResolutionValue: Runtime2 does not unwrap StandardResolutionValue resolver results, so completion fails with a ClassCastException")
    override fun `resolver returning StandardResolutionValue unwraps value and resolves children with standard policy`() {
        super<StandardResolutionValueContract>.`resolver returning StandardResolutionValue unwraps value and resolves children with standard policy`()
    }
}
