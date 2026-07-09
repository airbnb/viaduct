@file:Suppress("ForbiddenImport")

package viaduct.engine.api.instrumentation.resolver

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.runtime.instrumentation.resolver.ChainedResolverInstrumentation

class ChainedResolverInstrumentationTest {
    @Test
    fun `createInstrumentationState creates state for all instrumentations`() {
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
                resolverMetadata = ResolverMetadata.forMock("TestResolver")
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
                { expectedResult },
                parameters,
                state
            ).resolve()

            assertEquals(expectedResult, result)
            assertTrue(instr1ResolverExecutionCalled.get())
            assertTrue(instr2ResolverExecutionCalled.get())
        }

    @Test
    fun `beginFetchSelection begins all instrumentations and finishes in reverse order`() {
        val parameters = ViaductResolverInstrumentation.InstrumentFetchSelectionParameters("testSelection")
        val events = mutableListOf<String>()

        val instr1 = object : ViaductResolverInstrumentation {
            override fun beginFetchSelection(
                parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?,
            ): ViaductResolverInstrumentation.FetchSelectionInstrumentation {
                events.add("begin1")
                return ViaductResolverInstrumentation.FetchSelectionInstrumentation { events.add("finish1") }
            }
        }

        val instr2 = object : ViaductResolverInstrumentation {
            override fun beginFetchSelection(
                parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?,
            ): ViaductResolverInstrumentation.FetchSelectionInstrumentation {
                events.add("begin2")
                return ViaductResolverInstrumentation.FetchSelectionInstrumentation { events.add("finish2") }
            }
        }

        val chained = ChainedResolverInstrumentation(listOf(instr1, instr2))
        val state = chained.createInstrumentationState(ViaductResolverInstrumentation.CreateInstrumentationStateParameters())
        chained.beginFetchSelection(parameters, state).finish(null)

        assertEquals(listOf("begin1", "begin2", "finish2", "finish1"), events)
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
                { expectedResult },
                parameters,
                state
            ).check()

            assertEquals(expectedResult, result)
            assertTrue(instr1CheckerCalled.get())
            assertTrue(instr2CheckerCalled.get())
        }
}
