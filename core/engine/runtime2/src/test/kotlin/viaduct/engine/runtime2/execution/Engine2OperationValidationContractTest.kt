package viaduct.engine.runtime2.execution

import viaduct.engine.api.Engine
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.runtime.execution.OperationValidationContract
import viaduct.engine.runtime.execution.runIncrementalEngineTest

class Engine2OperationValidationContractTest : OperationValidationContract, Engine2QueryExecutionContract {
    override fun runSubscriptionTest(
        module: EngineTestModule,
        block: suspend (Engine) -> Unit,
    ) = module.runIncrementalEngineTest(engine2Enabled = true) { engine -> block(engine) }
}
