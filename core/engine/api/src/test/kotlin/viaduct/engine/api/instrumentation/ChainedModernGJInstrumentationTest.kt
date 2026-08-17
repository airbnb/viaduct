package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetchingEnvironment
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.ShadowFieldExecutionComparison
import viaduct.engine.api.spi.ShadowFieldExecutionResults

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
    fun `shadow field execution comparisons compose across instrumentations`() {
        val parameters = mockk<InstrumentationFieldParameters>()
        val state1 = object : InstrumentationState {}
        val state2 = object : InstrumentationState {}
        val results = mockk<ShadowFieldExecutionResults>()
        val comparison1 = mockk<ShadowFieldExecutionComparison> {
            every { compare(results) } returns Unit
        }
        val comparison2 = mockk<ShadowFieldExecutionComparison> {
            every { compare(results) } returns Unit
        }
        val instr1 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state1)
            every { requestShadowFieldExecution(parameters, state1) } returns comparison1
        }
        val instr2 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state2)
            every { requestShadowFieldExecution(parameters, state2) } returns comparison2
        }

        val chained = ChainedModernGJInstrumentation(listOf(instr1, instr2))
        val state = chained.createStateAsync(mockk())?.get()
        val combinedComparison =
            requireNotNull(chained.requestShadowFieldExecution(parameters, state))

        combinedComparison.compare(results)

        verify { comparison1.compare(results) }
        verify { comparison2.compare(results) }
    }

    @Test
    fun `shadow field execution comparison returns null when none are requested`() {
        assertNull(ShadowFieldExecutionComparison.combine(emptyList()))
    }

    @Test
    fun `shadow field execution comparison returns a single comparison unchanged`() {
        val comparison = ShadowFieldExecutionComparison {}

        assertSame(
            comparison,
            ShadowFieldExecutionComparison.combine(listOf(comparison)),
        )
    }

    @Test
    fun `shadow field execution comparison sends results to every successful comparison`() {
        val outcome =
            ShadowFieldExecutionResults.Outcome(
                rawValue = Result.success("raw value"),
                graphqlErrors = emptyList(),
            )
        val results =
            ShadowFieldExecutionResults(
                production = outcome,
                shadow = outcome,
            )
        val observedResults = mutableListOf<ShadowFieldExecutionResults>()
        val combinedComparison =
            requireNotNull(
                ShadowFieldExecutionComparison.combine(
                    listOf(
                        ShadowFieldExecutionComparison { observedResults += it },
                        ShadowFieldExecutionComparison { observedResults += it },
                    )
                )
            )

        combinedComparison.compare(results)

        assertEquals(listOf(results, results), observedResults)
    }

    @Test
    fun `instrumentAccessCheck chains all instrumentations`() {
        val parameters = mockk<InstrumentationExecutionStrategyParameters>()
        val dfe = mockk<DataFetchingEnvironment>()
        val state1 = object : InstrumentationState {}
        val state2 = object : InstrumentationState {}
        val initialChecker = mockk<CheckerExecutor>()
        val intermediateChecker = mockk<CheckerExecutor>()
        val finalChecker = mockk<CheckerExecutor>()

        val instr1 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state1)
            every { instrumentAccessCheck(initialChecker, dfe, parameters, state1) } returns intermediateChecker
        }
        val instr2 = mockk<ViaductModernGJInstrumentation> {
            every { createStateAsync(any()) } returns CompletableFuture.completedFuture(state2)
            every { instrumentAccessCheck(intermediateChecker, dfe, parameters, state2) } returns finalChecker
        }

        val chained = ChainedModernGJInstrumentation(listOf(instr1, instr2))
        val state = chained.createStateAsync(mockk())?.get()
        val result = chained.instrumentAccessCheck(initialChecker, dfe, parameters, state)

        assertNotNull(result)
        verify { instr1.instrumentAccessCheck(initialChecker, dfe, parameters, state1) }
        verify { instr2.instrumentAccessCheck(intermediateChecker, dfe, parameters, state2) }
    }
}
