package viaduct.engine.api.instrumentation.resolver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.ResolverMetadata

class ViaductResolverInstrumentationTest {
    @Test
    fun `DEFAULT instrumentation returns default state`() {
        val parameters = ViaductResolverInstrumentation.CreateInstrumentationStateParameters()
        val state = ViaductResolverInstrumentation.DEFAULT.createInstrumentationState(parameters)

        assertEquals(ViaductResolverInstrumentation.DEFAULT_INSTRUMENTATION_STATE, state)
    }

    @Test
    fun `DEFAULT instrumentation returns NOOP_ON_COMPLETED`() {
        val resolverMetadata = ResolverMetadata.forModern("TestResolver")
        val onExecuteResolverCompleted = ViaductResolverInstrumentation.DEFAULT.beginExecuteResolver(
            ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(resolverMetadata),
            null
        )
        assertEquals(ViaductResolverInstrumentation.NOOP_ON_COMPLETED, onExecuteResolverCompleted)

        val onFetchSelectionCompleted = ViaductResolverInstrumentation.DEFAULT.beginFetchSelection(
            ViaductResolverInstrumentation.InstrumentFetchSelectionParameters(
                selection = "testField"
            ),
            null
        )
        assertEquals(ViaductResolverInstrumentation.NOOP_ON_COMPLETED, onFetchSelectionCompleted)
    }
}
