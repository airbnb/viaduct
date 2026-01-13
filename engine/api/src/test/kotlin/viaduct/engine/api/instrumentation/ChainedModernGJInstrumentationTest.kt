package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation

class ChainedModernGJInstrumentationTest {
    @BeforeEach
    fun setUp() = MockKAnnotations.init(this)

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `beginFetchObject delegates to all instrumentations`() {
        val parameters = mockk<InstrumentationExecutionStrategyParameters>()
        val state1 = object : InstrumentationState {}
        val state2 = object : InstrumentationState {}
        val context1 = mockk<InstrumentationContext<Unit>>()
        val context2 = mockk<InstrumentationContext<Unit>>()

        val instr1 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state1)
            every { beginFetchObject(parameters, any()) } returns context1
        }
        val instr2 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state2)
            every { beginFetchObject(parameters, any()) } returns context2
        }

        val chained = ChainedModernGJInstrumentation(listOf(instr1, instr2))
        val state = chained.createStateAsync(mockk())?.get()
        val result = chained.beginFetchObject(parameters, state)

        assertNotNull(result)
        verify { instr1.beginFetchObject(parameters, state1) }
        verify { instr2.beginFetchObject(parameters, state2) }
    }

    @Test
    fun `beginCompleteObject delegates to all instrumentations`() {
        val parameters = mockk<InstrumentationExecutionStrategyParameters>()
        val state1 = object : InstrumentationState {}
        val state2 = object : InstrumentationState {}
        val context1 = mockk<InstrumentationContext<Any>>()
        val context2 = mockk<InstrumentationContext<Any>>()

        val instr1 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state1)
            every { beginCompleteObject(parameters, any()) } returns context1
        }
        val instr2 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state2)
            every { beginCompleteObject(parameters, any()) } returns context2
        }

        val chained = ChainedModernGJInstrumentation(listOf(instr1, instr2))
        val state = chained.createStateAsync(mockk())?.get()
        val result = chained.beginCompleteObject(parameters, state)

        assertNotNull(result)
        verify { instr1.beginCompleteObject(parameters, state1) }
        verify { instr2.beginCompleteObject(parameters, state2) }
    }

    @Test
    fun `instrumentAccessCheck chains all instrumentations`() {
        val parameters = mockk<InstrumentationExecutionStrategyParameters>()
        val state1 = object : InstrumentationState {}
        val state2 = object : InstrumentationState {}
        val initialChecker = mockk<CheckerExecutor>()
        val intermediateChecker = mockk<CheckerExecutor>()
        val finalChecker = mockk<CheckerExecutor>()

        val instr1 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state1)
            every { instrumentAccessCheck(initialChecker, parameters, state1) } returns intermediateChecker
        }
        val instr2 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state2)
            every { instrumentAccessCheck(intermediateChecker, parameters, state2) } returns finalChecker
        }

        val chained = ChainedModernGJInstrumentation(listOf(instr1, instr2))
        val state = chained.createStateAsync(mockk())?.get()
        val result = chained.instrumentAccessCheck(initialChecker, parameters, state)

        assertNotNull(result)
        verify { instr1.instrumentAccessCheck(initialChecker, parameters, state1) }
        verify { instr2.instrumentAccessCheck(intermediateChecker, parameters, state2) }
    }
}
