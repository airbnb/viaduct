package viaduct.engine.runtime.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.Engine1QueryExecutionContract

@ExperimentalCoroutinesApi
class RequiredSelectionsContractTest : RequiredSelectionsContract, Engine1QueryExecutionContract {
    @Test
    @Disabled("N/A: Engine1 rejects mutation resolver query selections")
    override fun `resolve mutation with queryValueFragment`() {
        super<RequiredSelectionsContract>.`resolve mutation with queryValueFragment`()
    }
}
