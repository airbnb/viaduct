package viaduct.engine.runtime2.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.runtime.execution.FieldDataLoaderContract

@ExperimentalCoroutinesApi
class Engine2FieldDataLoaderContractTest : FieldDataLoaderContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "Batching: Runtime2 dispatches each field resolution immediately, so the batch:3 assertion that proves batching cannot hold")
    override fun `batch field resolution clears field scope to prevent cross-field contamination`() {
        super<FieldDataLoaderContract>.`batch field resolution clears field scope to prevent cross-field contamination`()
    }
}
