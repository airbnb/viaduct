package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.Engine1QueryExecutionContract

class VariablesResolverContractTest : VariablesResolverContract, Engine1QueryExecutionContract {
    @Disabled("Disabled until validation of variables-provider behavior is in engine.")
    @Test
    override fun `variables provider -- returns extra variables`() {
        super<VariablesResolverContract>.`variables provider -- returns extra variables`()
    }

    @Disabled("Disabled until validation of variables-provider behavior is in engine.")
    @Test
    override fun `variables provider -- does not return declared variable value`() {
        super<VariablesResolverContract>.`variables provider -- does not return declared variable value`()
    }

    @Disabled("Disabled until validation of variables-provider behavior is in engine.")
    @Test
    override fun `invalid variable reference`() {
        super<VariablesResolverContract>.`invalid variable reference`()
    }
}
