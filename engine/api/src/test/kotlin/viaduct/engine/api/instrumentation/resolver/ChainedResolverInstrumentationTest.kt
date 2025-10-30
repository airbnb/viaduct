package viaduct.engine.api.instrumentation.resolver

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChainedResolverInstrumentationTest {
    @BeforeEach
    fun setUp() = MockKAnnotations.init(this)

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `createInstrumentationState creates state for all instrumentations`() {
        val parameters = ViaductResolverInstrumentation.CreateInstrumentationStateParameters(mockk())
        val state1 = object : ViaductResolverInstrumentation.InstrumentationState {}
        val state2 = object : ViaductResolverInstrumentation.InstrumentationState {}

        val instr1 = mockk<ViaductResolverInstrumentation> {
            every { createInstrumentationState(parameters) } returns state1
        }
        val instr2 = mockk<ViaductResolverInstrumentation> {
            every { createInstrumentationState(parameters) } returns state2
        }

        val chained = ChainedResolverInstrumentation(listOf(instr1, instr2))
        val result = chained.createInstrumentationState(parameters)

        assertNotNull(result)
        verify { instr1.createInstrumentationState(parameters) }
        verify { instr2.createInstrumentationState(parameters) }
    }

    @Test
    fun `beginExecuteResolver delegates to all instrumentations`() {
        val parameters = ViaductResolverInstrumentation.InstrumentExecuteResolverParameters()
        val state1 = object : ViaductResolverInstrumentation.InstrumentationState {}
        val state2 = object : ViaductResolverInstrumentation.InstrumentationState {}
        val onCompleted1 = mockk<ViaductResolverInstrumentation.OnCompleted>(relaxed = true)
        val onCompleted2 = mockk<ViaductResolverInstrumentation.OnCompleted>(relaxed = true)

        val instr1 = mockk<ViaductResolverInstrumentation> {
            every { createInstrumentationState(any()) } returns state1
            every { beginExecuteResolver(parameters, state1) } returns onCompleted1
        }
        val instr2 = mockk<ViaductResolverInstrumentation> {
            every { createInstrumentationState(any()) } returns state2
            every { beginExecuteResolver(parameters, state2) } returns onCompleted2
        }

        val chained = ChainedResolverInstrumentation(listOf(instr1, instr2))
        val state = chained.createInstrumentationState(ViaductResolverInstrumentation.CreateInstrumentationStateParameters(mockk()))
        val result = chained.beginExecuteResolver(parameters, state)

        assertNotNull(result)
        verify { instr1.beginExecuteResolver(parameters, state1) }
        verify { instr2.beginExecuteResolver(parameters, state2) }

        val testResult = "test result"
        result.onCompleted(testResult, null)

        verify { onCompleted1.onCompleted(testResult, null) }
        verify { onCompleted2.onCompleted(testResult, null) }

        val testError = RuntimeException("test error")
        result.onCompleted(null, testError)
        verify { onCompleted1.onCompleted(null, testError) }
        verify { onCompleted2.onCompleted(null, testError) }
    }

    @Test
    fun `beginFetchSelection delegates to all instrumentations`() {
        val parameters = ViaductResolverInstrumentation.InstrumentFetchSelectionParameters("testSelection")
        val state1 = object : ViaductResolverInstrumentation.InstrumentationState {}
        val state2 = object : ViaductResolverInstrumentation.InstrumentationState {}
        val onCompleted1 = mockk<ViaductResolverInstrumentation.OnCompleted>(relaxed = true)
        val onCompleted2 = mockk<ViaductResolverInstrumentation.OnCompleted>(relaxed = true)

        val instr1 = mockk<ViaductResolverInstrumentation> {
            every { createInstrumentationState(any()) } returns state1
            every { beginFetchSelection(parameters, state1) } returns onCompleted1
        }
        val instr2 = mockk<ViaductResolverInstrumentation> {
            every { createInstrumentationState(any()) } returns state2
            every { beginFetchSelection(parameters, state2) } returns onCompleted2
        }

        val chained = ChainedResolverInstrumentation(listOf(instr1, instr2))
        val state = chained.createInstrumentationState(ViaductResolverInstrumentation.CreateInstrumentationStateParameters(mockk()))
        val result = chained.beginFetchSelection(parameters, state)

        assertNotNull(result)
        verify { instr1.beginFetchSelection(parameters, state1) }
        verify { instr2.beginFetchSelection(parameters, state2) }

        val testResult = mapOf("key" to "value")
        result.onCompleted(testResult, null)

        verify { onCompleted1.onCompleted(testResult, null) }
        verify { onCompleted2.onCompleted(testResult, null) }

        val testError = RuntimeException("test error")
        result.onCompleted(null, testError)
        verify { onCompleted1.onCompleted(null, testError) }
        verify { onCompleted2.onCompleted(null, testError) }
    }
}
