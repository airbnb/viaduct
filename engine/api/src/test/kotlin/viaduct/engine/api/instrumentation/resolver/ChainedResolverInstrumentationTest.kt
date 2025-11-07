@file:Suppress("ForbiddenImport")

package viaduct.engine.api.instrumentation.resolver

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.ResolverMetadata

class ChainedResolverInstrumentationTest {
    @Test
    fun `createInstrumentationState creates state for all instrumentations`() {
        val parameters = ViaductResolverInstrumentation.CreateInstrumentationStateParameters()
        val state1 = object : ViaductResolverInstrumentation.InstrumentationState {}
        val state2 = object : ViaductResolverInstrumentation.InstrumentationState {}
        val instr1CreateStateCalled = AtomicBoolean(false)
        val instr2CreateStateCalled = AtomicBoolean(false)

        val instr1 = object : ViaductResolverInstrumentation {
            override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
                instr1CreateStateCalled.set(true)
                return state1
            }
        }

        val instr2 = object : ViaductResolverInstrumentation {
            override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
                instr2CreateStateCalled.set(true)
                return state2
            }
        }

        val chained = ChainedResolverInstrumentation(listOf(instr1, instr2))
        val state = chained.createInstrumentationState(ViaductResolverInstrumentation.CreateInstrumentationStateParameters())
        assert(state is ChainedResolverInstrumentation.ChainedInstrumentationState)
        val chainedState = state as ChainedResolverInstrumentation.ChainedInstrumentationState
        assertEquals(state1, chainedState.getState(instr1))
        assertEquals(state2, chainedState.getState(instr2))
        assertTrue(instr1CreateStateCalled.get())
        assertTrue(instr2CreateStateCalled.get())
    }

    @Test
    @ExperimentalCoroutinesApi
    fun `instrumentResolverExecution chains all instrumentations`() =
        runBlocking {
            val parameters = ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
                resolverMetadata = ResolverMetadata.forModern("TestResolver")
            )
            val instr1ResolverExecutionCalled = AtomicBoolean(false)
            val instr2ResolverExecutionCalled = AtomicBoolean(false)
            val expectedResult = "test result"

            val instr1 = object : ViaductResolverInstrumentation {
                override fun <T> instrumentResolverExecution(
                    resolver: ResolverFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): ResolverFunction<T> {
                    instr1ResolverExecutionCalled.set(true)
                    return super.instrumentResolverExecution(resolver, parameters, state)
                }
            }

            val instr2 = object : ViaductResolverInstrumentation {
                override fun <T> instrumentResolverExecution(
                    resolver: ResolverFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): ResolverFunction<T> {
                    instr2ResolverExecutionCalled.set(true)
                    return super.instrumentResolverExecution(resolver, parameters, state)
                }
            }

            val chained = ChainedResolverInstrumentation(listOf(instr1, instr2))
            val state = chained.createInstrumentationState(ViaductResolverInstrumentation.CreateInstrumentationStateParameters())
            val result = chained.instrumentResolverExecution(
                ResolverFunction { expectedResult },
                parameters,
                state
            ).resolve()

            assertEquals(expectedResult, result)
            assertTrue(instr1ResolverExecutionCalled.get())
            assertTrue(instr2ResolverExecutionCalled.get())
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `instrumentFetchSelection chains all instrumentations`() =
        runBlocking {
            val parameters = ViaductResolverInstrumentation.InstrumentFetchSelectionParameters("testSelection")
            val instr1FetchSelectionCalled = AtomicBoolean(false)
            val instr2FetchSelectionCalled = AtomicBoolean(false)
            val expectedResult = mapOf("key" to "value")

            val instr1 = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    instr1FetchSelectionCalled.set(true)
                    return super.instrumentFetchSelection(fetchFn, parameters, state)
                }
            }

            val instr2 = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    instr2FetchSelectionCalled.set(true)
                    return super.instrumentFetchSelection(fetchFn, parameters, state)
                }
            }

            val chained = ChainedResolverInstrumentation(listOf(instr1, instr2))
            val state = chained.createInstrumentationState(ViaductResolverInstrumentation.CreateInstrumentationStateParameters())
            val result = chained.instrumentFetchSelection(
                FetchFunction { expectedResult },
                parameters,
                state
            ).fetch()

            assertEquals(expectedResult, result)
            assertTrue(instr1FetchSelectionCalled.get())
            assertTrue(instr2FetchSelectionCalled.get())
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `instrumentAccessChecker chains all instrumentations`() =
        runBlocking {
            val checkerMetadata = CheckerMetadata(
                checkerName = "Himeji",
                typeName = "User",
                fieldName = "email"
            )
            val parameters = ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters(checkerMetadata)
            val instr1CheckerCalled = AtomicBoolean(false)
            val instr2CheckerCalled = AtomicBoolean(false)
            val expectedResult = "checker passed"

            val instr1 = object : ViaductResolverInstrumentation {
                override fun <T> instrumentAccessChecker(
                    checker: CheckerFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): CheckerFunction<T> {
                    instr1CheckerCalled.set(true)
                    return super.instrumentAccessChecker(checker, parameters, state)
                }
            }

            val instr2 = object : ViaductResolverInstrumentation {
                override fun <T> instrumentAccessChecker(
                    checker: CheckerFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): CheckerFunction<T> {
                    instr2CheckerCalled.set(true)
                    return super.instrumentAccessChecker(checker, parameters, state)
                }
            }

            val chained = ChainedResolverInstrumentation(listOf(instr1, instr2))
            val state = chained.createInstrumentationState(ViaductResolverInstrumentation.CreateInstrumentationStateParameters())
            val result = chained.instrumentAccessChecker(
                CheckerFunction { expectedResult },
                parameters,
                state
            ).check()

            assertEquals(expectedResult, result)
            assertTrue(instr1CheckerCalled.get())
            assertTrue(instr2CheckerCalled.get())
        }
}
