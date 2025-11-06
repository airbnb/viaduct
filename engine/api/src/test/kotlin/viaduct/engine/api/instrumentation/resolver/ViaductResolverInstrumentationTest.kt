@file:Suppress("ForbiddenImport")

package viaduct.engine.api.instrumentation.resolver

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
    fun `DEFAULT instrumentation executes resolver function`() =
        runBlocking {
            val expectedResult = "test result"
            val result = ViaductResolverInstrumentation.DEFAULT.instrumentResolverExecution(
                ResolverFunction { expectedResult },
                ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
                    resolverMetadata = ResolverMetadata.forModern("TestResolver")
                ),
                null
            ).resolve()
            assertEquals(expectedResult, result)
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `DEFAULT instrumentation executes fetch function`() =
        runBlocking {
            val expectedResult = "test field value"
            val result = ViaductResolverInstrumentation.DEFAULT.instrumentFetchSelection(
                FetchFunction { expectedResult },
                ViaductResolverInstrumentation.InstrumentFetchSelectionParameters(
                    selection = "testField"
                ),
                null
            ).fetch()
            assertEquals(expectedResult, result)
        }
}
