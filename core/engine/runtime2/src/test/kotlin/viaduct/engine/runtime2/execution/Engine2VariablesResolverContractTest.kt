package viaduct.engine.runtime2.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.execution.VariablesResolverContract

class Engine2VariablesResolverContractTest : VariablesResolverContract, Engine2QueryExecutionContract {
    @Disabled("ALT: Production treats an invalid variables-provider key set as request failure; qplan models the provider exception as an error on the owning field")
    @Test
    override fun `variables provider -- returns extra variables`() {
        super<VariablesResolverContract>.`variables provider -- returns extra variables`()
    }

    @Test
    fun `ALTERNATIVE variables provider -- returns extra variables`() {
        val result = executeQuery(providerModule(mapOf("varx" to 2, "extra" to 3)), "{foo}")
        assertEquals(null, result.getData<Any?>())
        assertEquals(listOf("foo"), result.errors.single().path)
        assertTrue(result.errors.single().message.contains("VariablesProvider returned invalid variables. Extra keys: extra"))
    }

    @Disabled("ALT: Production treats an invalid variables-provider key set as request failure; qplan models the provider exception as an error on the owning field")
    @Test
    override fun `variables provider -- does not return declared variable value`() {
        super<VariablesResolverContract>.`variables provider -- does not return declared variable value`()
    }

    @Test
    fun `ALTERNATIVE variables provider -- does not return declared variable value`() {
        val result = executeQuery(providerModule(emptyMap()), "{foo}")
        assertEquals(null, result.getData<Any?>())
        assertEquals(listOf("foo"), result.errors.single().path)
        assertTrue(result.errors.single().message.contains("VariablesProvider returned invalid variables. Missing keys: varx"))
    }

    @Disabled("ALT: Production permits object and Query RSS callbacks to bind the same variable name independently; qplan gives each resolver variable name one occurrence-local binding")
    @Test
    override fun `variables are coerced`() {
        super<VariablesResolverContract>.`variables are coerced`()
    }

    @Test
    fun `ALTERNATIVE variables are coerced`() {
        assertQuery(coercionModule("vary"), "{ foo }", "{data: {foo: 25}}")
    }

    @Disabled("N/A: Tests production pruning an unused callback-owned provider; qplan rejects unused declarative variable providers at registry construction")
    @Test
    override fun `variables resolver rss without a selection reference is missing from query plan index`() {
        super<VariablesResolverContract>.`variables resolver rss without a selection reference is missing from query plan index`()
    }

    @Disabled("ALT: Uses a callback-owned aliased-typename RSS; qplan folds that demand into the resolver object fragment and uses its supported no-RSS provider")
    @Test
    override fun `variable resolver required selection with aliased typename does not hang`() {
        super<VariablesResolverContract>.`variable resolver required selection with aliased typename does not hang`()
    }

    @Test
    fun `ALTERNATIVE variable resolver required selection with aliased typename does not hang`() {
        assertQueryWithTimeout(
            aliasedTypenameAlternativeModule(),
            "{ query { flag } }",
            "{data: {query: {flag: true}}}",
        )
    }
}
