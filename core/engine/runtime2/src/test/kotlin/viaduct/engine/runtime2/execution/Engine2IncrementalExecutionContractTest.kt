package viaduct.engine.runtime2.execution

import kotlinx.coroutines.CoroutineScope
import viaduct.engine.api.Engine
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.runtime.execution.IncrementalExecutionContract
import viaduct.engine.runtime.execution.runIncrementalEngineTest

class Engine2IncrementalExecutionContractTest : IncrementalExecutionContract {
    override fun runIncrementalTest(
        module: EngineTestModule,
        incrementalEnabled: Boolean,
        block: suspend CoroutineScope.(Engine) -> Unit,
    ) = module.runIncrementalEngineTest(engine2Enabled = true, incrementalEnabled, block)
}
