package viaduct.engine.runtime2.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import viaduct.engine.runtime.execution.VariablesResolverContract

class Engine2VariablesResolverContractTest : VariablesResolverContract, Engine2QueryExecutionContract {
    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production treats an invalid variables-provider key set as request failure; qplan models the provider exception as an error on the owning field"
    )
    override fun `variables provider -- returns extra variables`() {
        super<VariablesResolverContract>.`variables provider -- returns extra variables`()
    }

    @Engine2Alternate(original = "variables provider -- returns extra variables")
    fun `ALTERNATIVE variables provider -- returns extra variables`() {
        val result = executeQuery(providerModule(mapOf("varx" to 2, "extra" to 3)), "{foo}")
        assertEquals(null, result.getData<Any?>())
        assertEquals(listOf("foo"), result.errors.single().path)
        assertTrue(result.errors.single().message.contains("VariablesProvider returned invalid variables. Extra keys: extra"))
    }

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production treats an invalid variables-provider key set as request failure; qplan models the provider exception as an error on the owning field"
    )
    override fun `variables provider -- does not return declared variable value`() {
        super<VariablesResolverContract>.`variables provider -- does not return declared variable value`()
    }

    @Engine2Alternate(original = "variables provider -- does not return declared variable value")
    fun `ALTERNATIVE variables provider -- does not return declared variable value`() {
        val result = executeQuery(providerModule(emptyMap()), "{foo}")
        assertEquals(null, result.getData<Any?>())
        assertEquals(listOf("foo"), result.errors.single().path)
        assertTrue(result.errors.single().message.contains("VariablesProvider returned invalid variables. Missing keys: varx"))
    }

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Production permits object and Query RSS callbacks to bind the same variable name independently; qplan gives each resolver variable name one occurrence-local binding"
    )
    override fun `variables are coerced`() {
        super<VariablesResolverContract>.`variables are coerced`()
    }

    @Engine2Alternate(original = "variables are coerced")
    fun `ALTERNATIVE variables are coerced`() {
        assertQuery(coercionModule("vary"), "{ foo }", "{data: {foo: 25}}")
    }

    @Engine2Disabled(
        Engine2DisabledKind.NA,
        "Tests production pruning an unused callback-owned provider; qplan rejects unused declarative variable providers at registry construction"
    )
    override fun `variables resolver rss without a selection reference is missing from query plan index`() {
        super<VariablesResolverContract>.`variables resolver rss without a selection reference is missing from query plan index`()
    }

    @Engine2Disabled(
        Engine2DisabledKind.ALT,
        "Uses a callback-owned aliased-typename RSS; qplan folds that demand into the resolver object fragment and uses its supported no-RSS provider"
    )
    override fun `variable resolver required selection with aliased typename does not hang`() {
        super<VariablesResolverContract>.`variable resolver required selection with aliased typename does not hang`()
    }

    @Engine2Alternate(original = "variable resolver required selection with aliased typename does not hang")
    fun `ALTERNATIVE variable resolver required selection with aliased typename does not hang`() {
        assertQueryWithTimeout(
            aliasedTypenameAlternativeModule(),
            "{ query { flag } }",
            "{data: {query: {flag: true}}}",
        )
    }
}
