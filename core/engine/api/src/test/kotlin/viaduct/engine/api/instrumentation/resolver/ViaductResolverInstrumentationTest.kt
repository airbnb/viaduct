@file:Suppress("ForbiddenImport")

package viaduct.engine.api.instrumentation.resolver

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
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
    @ExperimentalCoroutinesApi
    fun `DEFAULT instrumentation executes resolver function`(): Unit =
        runBlocking {
            val expectedResult = "test result"
            val result = ViaductResolverInstrumentation.DEFAULT.instrumentResolverExecution(
                ResolverFunction { expectedResult },
                ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
                    resolverMetadata = ResolverMetadata.forMock("TestResolver")
                ),
                null
            ).resolve()
            assertEquals(expectedResult, result)
        }

    @Test
    fun `DEFAULT instrumentation accepts batch notifications without overrides`() {
        val metadata = ResolverMetadata.forMock("TestResolver")

        assertDoesNotThrow {
            ViaductResolverInstrumentation.DEFAULT.onBatchResolverDispatch(metadata, batchSize = 3, state = null)
            ViaductResolverInstrumentation.DEFAULT.onBatchResolverInvocation(metadata, batchSize = 2, state = null)
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun `DEFAULT instrumentation returns noop fetch selection instrumentation`() {
        ViaductResolverInstrumentation.DEFAULT.beginFetchSelection(
            ViaductResolverInstrumentation.InstrumentFetchSelectionParameters(
                selection = "testField"
            ),
            null
        ).finish(null)
    }
}
