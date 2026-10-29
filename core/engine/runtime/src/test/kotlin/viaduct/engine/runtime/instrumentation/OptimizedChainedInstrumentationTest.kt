package viaduct.engine.runtime.instrumentation

import graphql.ExecutionInput
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetchingEnvironment
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifySequence
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationAdapter
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.ShadowFieldExecutionComparison

class OptimizedChainedInstrumentationTest {
    private lateinit var noInteractionInstrumentationBase: ViaductInstrumentationBase
    private lateinit var noInteractionInstrumentation: ViaductInstrumentationAdapter

    @BeforeEach
    fun setUp() {
        noInteractionInstrumentation = mockk()
        noInteractionInstrumentationBase = mockk()
        every { noInteractionInstrumentationBase.asStandardInstrumentation() } returns noInteractionInstrumentation
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    class TestBeginFetchObjectInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginFetchObject {
        var called = false

        override fun beginFetchObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Unit> {
            called = true
            return noOp()
        }
    }

    @Test
    fun `test beginFetchedObject optimization`() {
        val beginFetchObjectInstrumentation = TestBeginFetchObjectInstrumentation()
        val optimized = OptimizedChainedInstrumentation(
            listOf(beginFetchObjectInstrumentation, noInteractionInstrumentationBase)
        )
        val params = mockk<InstrumentationExecutionStrategyParameters>()
        val state = mockk<InstrumentationState>()
        val context = optimized.beginFetchObject(params, state)
        assertNotNull(context)
        assert(beginFetchObjectInstrumentation.called)
        verify { noInteractionInstrumentation wasNot Called }
    }

    class TestShadowFieldExecutionInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithShadowFieldExecution {
        var called = false

        override fun requestShadowFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?,
        ): ShadowFieldExecutionComparison? {
            called = true
            return null
        }
    }

    @Test
    fun `test shadow field execution optimization`() {
        val instrumentation = TestShadowFieldExecutionInstrumentation()
        val optimized =
            OptimizedChainedInstrumentation(
                listOf(instrumentation, noInteractionInstrumentationBase)
            )

        optimized.requestShadowFieldExecution(mockk(), mockk())

        assert(instrumentation.called)
        verify { noInteractionInstrumentation wasNot Called }
    }

    class TestBeginCompleteObjectInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginCompleteObject {
        var called = false

        override fun beginCompleteObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any> {
            called = true
            return noOp()
        }
    }

    @Test
    fun `test beginCompleteObject optimization`() {
        val beginCompleteObjectInstrumentation = TestBeginCompleteObjectInstrumentation()
        val optimized = OptimizedChainedInstrumentation(
            listOf(beginCompleteObjectInstrumentation, noInteractionInstrumentationBase)
        )
        val params = mockk<InstrumentationExecutionStrategyParameters>()
        val state = mockk<InstrumentationState>()
        val context = optimized.beginCompleteObject(params, state)
        assertNotNull(context)
        assert(beginCompleteObjectInstrumentation.called)
        verify { noInteractionInstrumentation wasNot Called }
    }

    class TestInstrumentAccessCheckInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithInstrumentAccessCheck {
        var called = false

        override fun instrumentAccessCheck(
            checkerExecutor: CheckerExecutor,
            dataFetchingEnvironment: DataFetchingEnvironment,
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): CheckerExecutor {
            called = true
            return checkerExecutor
        }
    }

    @Test
    fun `test instrumentAccessCheck optimization`() {
        val instrumentAccessCheckInstrumentation = TestInstrumentAccessCheckInstrumentation()
        val optimized = OptimizedChainedInstrumentation(
            listOf(instrumentAccessCheckInstrumentation, noInteractionInstrumentationBase)
        )
        val params = mockk<InstrumentationExecutionStrategyParameters>()
        val state = mockk<InstrumentationState>()
        val checkerExecutor = mockk<CheckerExecutor>()
        val result = optimized.instrumentAccessCheck(checkerExecutor, mockk<DataFetchingEnvironment>(), params, state)
        assertNotNull(result)
        assert(instrumentAccessCheckInstrumentation.called)
        verify { noInteractionInstrumentation wasNot Called }
    }

    private class TestBeginFieldFetchInstrumentation(
        private val context: InstrumentationContext<Any>?
    ) : ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginFieldFetch {
        val state: InstrumentationState = object : InstrumentationState {}
        var calls = 0
        var receivedParameters: InstrumentationFieldFetchParameters? = null
        var receivedState: InstrumentationState? = null

        override fun createState(parameters: InstrumentationCreateStateParameters) = state

        override fun beginFieldFetch(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            calls++
            receivedParameters = parameters
            receivedState = state
            return context
        }
    }

    @Test
    fun `beginFieldFetching only calls participants and forwards their states and callbacks in order`() {
        val firstContext = mockk<InstrumentationContext<Any>>(relaxed = true)
        val secondContext = mockk<InstrumentationContext<Any>>(relaxed = true)
        val first = TestBeginFieldFetchInstrumentation(firstContext)
        val second = TestBeginFieldFetchInstrumentation(secondContext)
        every { noInteractionInstrumentation.createStateAsync(any()) } returns CompletableFuture.completedFuture(null)
        every { noInteractionInstrumentation.beginFieldFetching(any(), any()) } returns FieldFetchingInstrumentationContext.NOOP
        val optimized = OptimizedChainedInstrumentation(listOf(first, noInteractionInstrumentationBase, second))
        val state = optimized.createStateAsync(mockk())!!.join()
        clearMocks(noInteractionInstrumentation, answers = false)
        val parameters = mockk<InstrumentationFieldFetchParameters>()

        val context = optimized.beginFieldFetching(parameters, state)!!
        val result = Any()
        val error = IllegalStateException("field failed")
        context.onDispatched()
        context.onCompleted(result, error)

        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertSame(parameters, first.receivedParameters)
        assertSame(parameters, second.receivedParameters)
        assertSame(first.state, first.receivedState)
        assertSame(second.state, second.receivedState)
        verifySequence {
            firstContext.onDispatched()
            secondContext.onDispatched()
            firstContext.onCompleted(result, error)
            secondContext.onCompleted(result, error)
        }
        verify { noInteractionInstrumentation wasNot Called }
    }

    @Test
    fun `beginFieldFetching accepts no participants or null participant contexts`() {
        for (instrumentations in listOf(emptyList(), listOf(TestBeginFieldFetchInstrumentation(null)))) {
            val context = OptimizedChainedInstrumentation(instrumentations).beginFieldFetching(mockk(), null)!!
            context.onDispatched()
            context.onCompleted(null, null)
        }
    }

    @Test
    fun `beginFieldFetching preserves conditional instrumentation enablement`() {
        for (enabled in listOf(false, true)) {
            val delegateContext = mockk<InstrumentationContext<Any>>(relaxed = true)
            val delegateState = object : InstrumentationState {}
            val instrumentation = object : ConditionallyEnabledInstrumentation(), IViaductInstrumentation.WithBeginFieldFetch {
                override fun isInstrumentationEnabled(executionInput: ExecutionInput) = enabled

                override fun createState(parameters: InstrumentationCreateStateParameters) = delegateState

                override fun beginFieldFetch(
                    parameters: InstrumentationFieldFetchParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any> {
                    assertSame(delegateState, state)
                    return delegateContext
                }
            }
            val optimized = OptimizedChainedInstrumentation(listOf(instrumentation))
            val input = ExecutionInput.newExecutionInput("{ field }").build()
            val state = optimized.createStateAsync(InstrumentationCreateStateParameters(mockk(), input))!!.join()

            optimized.beginFieldFetching(mockk(), state)!!.onCompleted(null, null)

            verify(exactly = if (enabled) 1 else 0) { delegateContext.onCompleted(null, null) }
        }
    }
}
