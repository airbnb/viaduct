package viaduct.engine.runtime2.execution

import viaduct.engine.runtime.execution.ParentFieldRequiredSelectionsContract

class Engine2ParentFieldRequiredSelectionsContractTest : ParentFieldRequiredSelectionsContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "ParentField: Runtime2 requires exactly one compatible producer per @parent type, so a Child type shared by sibling fields is rejected")
    override fun `each child field can read its own parent's name`() {
        super<ParentFieldRequiredSelectionsContract>.`each child field can read its own parent's name`()
    }

    @Engine2Disabled(Engine2DisabledKind.NA, "Tests production field instrumentation behavior, outside runtime2 resolver correctness")
    override fun `execution skips field instrumentation for parent field itself`() {
        super<ParentFieldRequiredSelectionsContract>.`execution skips field instrumentation for parent field itself`()
    }
}
