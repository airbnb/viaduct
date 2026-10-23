package viaduct.engine.runtime2.execution

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.execution.ParentFieldRequiredSelectionsContract

class Engine2ParentFieldRequiredSelectionsContractTest : ParentFieldRequiredSelectionsContract, Engine2QueryExecutionContract {
    @Test
    @Disabled("TODO: ParentFieldChk: Field checkers on parent backedges are not enforced")
    override fun `parent field checker denies restricted data like equivalent normal field`() {
        super<ParentFieldRequiredSelectionsContract>.`parent field checker denies restricted data like equivalent normal field`()
    }

    @Test
    @Disabled("TODO: ParentFieldChk: Field checkers on parent backedges are not executed")
    override fun `execution resolves checker required selection sets for parent field itself`() {
        super<ParentFieldRequiredSelectionsContract>.`execution resolves checker required selection sets for parent field itself`()
    }

    @Test
    @Disabled("N/A: Tests production field instrumentation behavior, outside qplan resolver correctness")
    override fun `execution runs checker but skips field instrumentation for parent field itself`() {
        super<ParentFieldRequiredSelectionsContract>.`execution runs checker but skips field instrumentation for parent field itself`()
    }
}
