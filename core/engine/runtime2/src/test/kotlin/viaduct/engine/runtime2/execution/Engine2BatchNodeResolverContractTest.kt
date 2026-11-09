package viaduct.engine.runtime2.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.runtime.execution.BatchNodeResolverContract

@ExperimentalCoroutinesApi
class Engine2BatchNodeResolverContractTest : BatchNodeResolverContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "Batching: Runtime2 dispatches each node resolution immediately, so batches hold 1 selector instead of 3")
    override fun `node batch resolver batches`() {
        super<BatchNodeResolverContract>.`node batch resolver batches`()
    }
}
