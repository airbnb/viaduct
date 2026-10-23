package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.Engine1QueryExecutionContract

class BasicFeatureExecutionTest : BasicFeatureExecutionContract, Engine1QueryExecutionContract {
    @Test
    @Disabled("Engine1 loops when fieldWithValue and a checker share the same field")
    override fun `resolver with checker test`() {
        super<BasicFeatureExecutionContract>.`resolver with checker test`()
    }
}
