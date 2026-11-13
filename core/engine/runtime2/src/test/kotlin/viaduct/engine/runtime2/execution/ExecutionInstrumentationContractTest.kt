package viaduct.engine.runtime2.execution

import viaduct.engine.runtime.execution.ExecutionInstrumentationContract
import viaduct.engine.runtime.instrumentation.ScopeInstrumentation

class ExecutionInstrumentationContractTest : ExecutionInstrumentationContract, Engine2QueryExecutionContract {
    override val executionInstrumentation = ScopeInstrumentation().asStandardInstrumentation
}
