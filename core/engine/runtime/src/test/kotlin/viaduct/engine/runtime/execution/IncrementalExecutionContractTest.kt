package viaduct.engine.runtime.execution

import kotlinx.coroutines.CoroutineScope
import viaduct.engine.api.Engine
import viaduct.engine.api.mocks.EngineTestModule

class IncrementalExecutionContractTest : IncrementalExecutionContract {
    override fun runIncrementalTest(
        module: EngineTestModule,
        incrementalEnabled: Boolean,
        block: suspend CoroutineScope.(Engine) -> Unit,
    ) = module.runIncrementalEngineTest(engine2Enabled = false, incrementalEnabled, block)
}
