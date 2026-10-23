package viaduct.engine.runtime.execution

import viaduct.engine.EngineConfiguration
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.runtime.Engine1QueryExecutionContract
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

class SelectiveFieldExecutionContractTest : SelectiveFieldExecutionContract, Engine1QueryExecutionContract {
    override val selectiveFieldConfig: EngineConfiguration =
        EngineConfiguration.featureTestDefault.copy(
            flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION)
        )
}
