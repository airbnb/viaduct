package viaduct.engine.runtime2.execution

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.runtime.execution.EngineExecutionContract

class Engine2EngineExecutionContractTest : EngineExecutionContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "Incremental: Runtime2 does not return an incremental result before deferred work completes")
    override fun `execute returns an incremental result before deferred work completes`() {
        super<EngineExecutionContract>.`execute returns an incremental result before deferred work completes`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "Incremental: Runtime2 does not preserve extensions on incremental results")
    override fun `instrumentation can add an extension without losing deferred results`() {
        super<EngineExecutionContract>.`instrumentation can add an extension without losing deferred results`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "Incremental: Runtime2 does not return an incremental result to cancel")
    override fun `caller can cancel deferred work without collecting updates`() {
        super<EngineExecutionContract>.`caller can cancel deferred work without collecting updates`()
    }

    // TODO: Incremental: Runtime2 does not deliver incremental results yet, so only the non-incremental value runs
    @ParameterizedTest
    @ValueSource(booleans = [false])
    override fun `initial and deferred resolvers use the configured dispatcher`(incremental: Boolean) {
        super<EngineExecutionContract>.`initial and deferred resolvers use the configured dispatcher`(incremental)
    }
}
