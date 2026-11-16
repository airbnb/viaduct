package viaduct.engine.runtime2.execution

import viaduct.engine.runtime.execution.ExecutionGroupsContract

class Engine2ExecutionGroupsContractTest : ExecutionGroupsContract, Engine2QueryExecutionContract {
    // TODO: Incremental: Runtime2 does not deliver incremental results yet, so only the non-incremental mode runs
    override val incrementalExecutionModes = listOf(false)
}
