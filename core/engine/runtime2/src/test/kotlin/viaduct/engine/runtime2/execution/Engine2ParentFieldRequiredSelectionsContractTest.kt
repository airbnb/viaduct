package viaduct.engine.runtime2.execution

import viaduct.engine.runtime.execution.ParentFieldRequiredSelectionsContract

class Engine2ParentFieldRequiredSelectionsContractTest : ParentFieldRequiredSelectionsContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.NA, "Tests production field instrumentation behavior, outside qplan resolver correctness")
    override fun `execution skips field instrumentation for parent field itself`() {
        super<ParentFieldRequiredSelectionsContract>.`execution skips field instrumentation for parent field itself`()
    }
}
