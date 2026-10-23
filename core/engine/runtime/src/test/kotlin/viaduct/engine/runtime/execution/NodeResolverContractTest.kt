package viaduct.engine.runtime.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.Engine1QueryExecutionContract

@ExperimentalCoroutinesApi
class NodeResolverContractTest : NodeResolverContract, Engine1QueryExecutionContract {
    @Test
    @Disabled("flaky")
    override fun `node resolver reads from dataloader cache`() {
        super<NodeResolverContract>.`node resolver reads from dataloader cache`()
    }
}
