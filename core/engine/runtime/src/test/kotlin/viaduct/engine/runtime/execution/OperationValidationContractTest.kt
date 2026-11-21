package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertTrue
import viaduct.engine.api.Engine
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.runtime.Engine1QueryExecutionContract

class OperationValidationContractTest : OperationValidationContract, Engine1QueryExecutionContract {
    override fun runSubscriptionTest(
        module: EngineTestModule,
        block: suspend (Engine) -> Unit,
    ) = module.runIncrementalEngineTest(engine2Enabled = false) { engine ->
        assertTrue(engine.supportsIncrementalExecution, "Engine1 must support incremental execution")
        block(engine)
    }
}
