package viaduct.engine.runtime2.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.runtime.execution.BatchFieldResolverContract

@ExperimentalCoroutinesApi
class Engine2BatchFieldResolverContractTest : BatchFieldResolverContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "Batching: Runtime2 dispatches each field resolution immediately, so batches hold 1 selector instead of 2")
    override fun `field batch resolver batches in a tick`() {
        super<BatchFieldResolverContract>.`field batch resolver batches in a tick`()
    }
}
