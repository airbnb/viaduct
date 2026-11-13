package viaduct.engine.runtime.execution

import viaduct.engine.runtime.Engine1QueryExecutionContract
import viaduct.engine.runtime.instrumentation.ScopeInstrumentation

class ExecutionInstrumentationContractTest : ExecutionInstrumentationContract, Engine1QueryExecutionContract {
    override val executionInstrumentation = ScopeInstrumentation().asStandardInstrumentation
    override val mixedIntrospectionError = "Introspective queries cannot select non-introspective fields."
}
