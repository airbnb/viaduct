package viaduct.engine.runtime2.execution

import viaduct.engine.runtime.execution.NodeDataLoaderContract

class Engine2NodeDataLoaderContractTest : NodeDataLoaderContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "Batching: Runtime2 dispatches each node resolution immediately, so the batch:3 assertion that proves batching cannot hold")
    override fun `batch node resolution clears field scope to prevent cross-field contamination`() {
        super<NodeDataLoaderContract>.`batch node resolution clears field scope to prevent cross-field contamination`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "Caller: Runtime2 does not set fieldScope.caller for fields selected under a node resolver")
    override fun `node resolver is the caller for fields selected under the node`() {
        super<NodeDataLoaderContract>.`node resolver is the caller for fields selected under the node`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "Caller: Runtime2 does not set fieldScope.caller for a nested RSS-backed resolver under a node resolver")
    override fun `node resolver is the caller for a nested RSS-backed resolver under the node`() {
        super<NodeDataLoaderContract>.`node resolver is the caller for a nested RSS-backed resolver under the node`()
    }
}
