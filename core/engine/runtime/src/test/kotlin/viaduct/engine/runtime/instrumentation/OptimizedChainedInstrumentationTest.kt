package viaduct.engine.runtime.instrumentation

import graphql.ExecutionInput
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.InstrumentNodeFetchingParameters
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

    private class TestBeginNodeFetchingInstrumentation(
        private val context: InstrumentationContext<Any>?
    ) : ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginNodeFetching {
        val state: InstrumentationState = object : InstrumentationState {}
        var calls = 0
        var receivedParameters: InstrumentNodeFetchingParameters? = null
        var receivedState: InstrumentationState? = null

        override fun createState(parameters: InstrumentationCreateStateParameters) = state

        override fun beginNodeFetching(
            parameters: InstrumentNodeFetchingParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            calls++
            receivedParameters = parameters
            receivedState = state
            return context
        }
    }

    @Test
    fun `beginNodeFetching only calls participants and forwards their states and callbacks in order`() {
        val firstContext = mockk<InstrumentationContext<Any>>(relaxed = true)
        val secondContext = mockk<InstrumentationContext<Any>>(relaxed = true)
        val first = TestBeginNodeFetchingInstrumentation(firstContext)
        val withoutContext = TestBeginNodeFetchingInstrumentation(null)
        val second = TestBeginNodeFetchingInstrumentation(secondContext)
        every { noInteractionInstrumentation.createStateAsync(any()) } returns CompletableFuture.completedFuture(null)
        every { noInteractionInstrumentation.beginNodeFetching(any(), any()) } returns noOp()
        val optimized = OptimizedChainedInstrumentation(listOf(first, noInteractionInstrumentationBase, withoutContext, second))
        val state = optimized.createStateAsync(mockk())!!.join()
        clearMocks(noInteractionInstrumentation, answers = false)
        val parameters = InstrumentNodeFetchingParameters(null, null)

        val context = optimized.beginNodeFetching(parameters, state)!!
        val result = Any()
        val error = IllegalStateException("node failed")
        context.onDispatched()
        context.onCompleted(result, error)

        for (instrumentation in listOf(first, withoutContext, second)) {
            assertEquals(1, instrumentation.calls)
            assertSame(parameters, instrumentation.receivedParameters)
            assertSame(instrumentation.state, instrumentation.receivedState)
        }
        verifySequence {
            firstContext.onDispatched()
            secondContext.onDispatched()
            firstContext.onCompleted(result, error)
            secondContext.onCompleted(result, error)
        }
        verify { noInteractionInstrumentation wasNot Called }
    }

    @Test
    fun `beginNodeFetching accepts no participants or null participant contexts`() {
        for (instrumentations in listOf(emptyList(), listOf(ViaductInstrumentationBase()), listOf(TestBeginNodeFetchingInstrumentation(null)))) {
            val context = OptimizedChainedInstrumentation(instrumentations).beginNodeFetching(InstrumentNodeFetchingParameters(null, null), null)!!
            context.onDispatched()
            context.onCompleted(null, null)
        }
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
    fun `beginFieldFetching combines modern and legacy participants with conditional gating`() {
        for (enabled in listOf(false, true)) {
            val legacyContext = mockk<InstrumentationContext<Any>>(relaxed = true)
            val modernContext = mockk<FieldFetchingInstrumentationContext>(relaxed = true)
            val dualContext = mockk<FieldFetchingInstrumentationContext>(relaxed = true)
            val legacy = TestBeginFieldFetchInstrumentation(legacyContext)
            val delegateState = object : InstrumentationState {}
            val expectedParameters = mockk<InstrumentationFieldFetchParameters>()
            var calls = 0
            val modern = object : ConditionallyEnabledInstrumentation(), IViaductInstrumentation.WithBeginFieldFetching {
                override fun isInstrumentationEnabled(executionInput: ExecutionInput) = enabled

                override fun createState(parameters: InstrumentationCreateStateParameters) = delegateState

                override fun beginFieldFetching(
                    parameters: InstrumentationFieldFetchParameters,
                    state: InstrumentationState?
                ): FieldFetchingInstrumentationContext {
                    calls++
                    assertSame(delegateState, state)
                    assertSame(expectedParameters, parameters)
                    return modernContext
                }
            }
            val dual = object :
                ViaductInstrumentationBase(),
                IViaductInstrumentation.WithBeginFieldFetch,
                IViaductInstrumentation.WithBeginFieldFetching {
                override fun beginFieldFetch(
                    parameters: InstrumentationFieldFetchParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any>? = error("legacy hook must not be selected when the modern hook is implemented")

                override fun beginFieldFetching(
                    parameters: InstrumentationFieldFetchParameters,
                    state: InstrumentationState?
                ): FieldFetchingInstrumentationContext = dualContext
            }
            val optimized = OptimizedChainedInstrumentation(listOf(modern, ViaductInstrumentationBase(), legacy, dual))
            val input = ExecutionInput.newExecutionInput("{ field }").build()
            val state = optimized.createStateAsync(InstrumentationCreateStateParameters(mockk(), input))!!.join()
            val result = Any()
            val error = IllegalStateException("field failed")

            val context = optimized.beginFieldFetching(expectedParameters, state)!!
            context.onDispatched()
            context.onFetchedValue(result)
            context.onCompleted(result, error)

            assertEquals(1, legacy.calls)
            assertEquals(if (enabled) 1 else 0, calls)
            if (enabled) {
                verifySequence {
                    modernContext.onDispatched()
                    legacyContext.onDispatched()
                    dualContext.onDispatched()
                    modernContext.onFetchedValue(result)
                    dualContext.onFetchedValue(result)
                    modernContext.onCompleted(result, error)
                    legacyContext.onCompleted(result, error)
                    dualContext.onCompleted(result, error)
                }
            } else {
                verify { modernContext wasNot Called }
                verifySequence {
                    legacyContext.onDispatched()
                    dualContext.onDispatched()
                    dualContext.onFetchedValue(result)
                    legacyContext.onCompleted(result, error)
                    dualContext.onCompleted(result, error)
                }
            }
        }
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

    private class CompletionParticipant(
        private val context: InstrumentationContext<Any>?
    ) : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFieldCompletion {
        var calls = 0

        override fun beginFieldCompletion(
            parameters: InstrumentationFieldCompleteParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            calls++
            return context
        }
    }

    private class FetchingParticipant(
        private val context: FieldFetchingInstrumentationContext?
    ) : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFieldFetching {
        override fun beginFieldFetching(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): FieldFetchingInstrumentationContext? = context
    }

    @Test
    fun `field execution preserves context fast paths and callbacks`() {
        val expectedParameters = mockk<InstrumentationFieldParameters>()
        assertContextFastPaths(
            participant = { context ->
                object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFieldExecution {
                    override fun beginFieldExecution(
                        parameters: InstrumentationFieldParameters,
                        state: InstrumentationState?
                    ): InstrumentationContext<Any>? {
                        assertSame(expectedParameters, parameters)
                        return context
                    }
                }
            },
            begin = { it.beginFieldExecution(expectedParameters, null) }
        )
    }

    @Test
    fun `list completion preserves context fast paths and callbacks`() {
        val expectedParameters = mockk<InstrumentationFieldCompleteParameters>()
        assertContextFastPaths(
            participant = { context ->
                object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFieldListCompletion {
                    override fun beginFieldListCompletion(
                        parameters: InstrumentationFieldCompleteParameters,
                        state: InstrumentationState?
                    ): InstrumentationContext<Any>? {
                        assertSame(expectedParameters, parameters)
                        return context
                    }
                }
            },
            begin = { it.beginFieldListCompletion(expectedParameters, null) }
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy field fetch preserves context fast paths and callbacks`() {
        val expectedParameters = mockk<InstrumentationFieldFetchParameters>()
        assertContextFastPaths(
            participant = { context ->
                object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFieldFetch {
                    override fun beginFieldFetch(
                        parameters: InstrumentationFieldFetchParameters,
                        state: InstrumentationState?
                    ): InstrumentationContext<Any>? {
                        assertSame(expectedParameters, parameters)
                        return context
                    }
                }
            },
            begin = { it.beginFieldFetch(expectedParameters, null) }
        )
    }

    private fun assertContextFastPaths(
        participant: (InstrumentationContext<Any>?) -> ViaductInstrumentationBase,
        begin: (OptimizedChainedInstrumentation) -> InstrumentationContext<Any>?
    ) {
        assertSame(noOp<Any>(), begin(OptimizedChainedInstrumentation(emptyList())))
        assertSame(noOp<Any>(), begin(OptimizedChainedInstrumentation(listOf(participant(null), participant(noOp())))))

        val first = mockk<InstrumentationContext<Any>>(relaxed = true)
        val second = mockk<InstrumentationContext<Any>>(relaxed = true)
        val third = mockk<InstrumentationContext<Any>>(relaxed = true)
        assertSame(first, begin(OptimizedChainedInstrumentation(listOf(participant(null), participant(first), participant(noOp())))))
        val optimized = OptimizedChainedInstrumentation(
            listOf(participant(first), participant(null), participant(second), participant(noOp()), participant(third), noInteractionInstrumentationBase)
        )
        val result = Any()
        val error = IllegalStateException("field failed")

        val context = begin(optimized)!!
        context.onDispatched()
        context.onCompleted(result, error)

        verifySequence {
            first.onDispatched()
            second.onDispatched()
            third.onDispatched()
            first.onCompleted(result, error)
            second.onCompleted(result, error)
            third.onCompleted(result, error)
        }
        verify { noInteractionInstrumentation wasNot Called }
    }

    @Test
    fun `hooks without participants return shared no-op contexts`() {
        val optimized = OptimizedChainedInstrumentation(listOf(ViaductInstrumentationBase()))

        assertSame(noOp<Any>(), optimized.beginFieldExecution(mockk(), null))
        assertSame(noOp<Any>(), optimized.beginFieldCompletion(mockk(), null))
        assertSame(noOp<Any>(), optimized.beginFieldListCompletion(mockk(), null))
        assertSame(noOp<Unit>(), optimized.beginFetchObject(mockk(), null))
        assertSame(noOp<Any>(), optimized.beginCompleteObject(mockk(), null))
        assertSame(noOp<Any>(), optimized.beginNodeFetching(InstrumentNodeFetchingParameters(null, null), null))
        assertSame(FieldFetchingInstrumentationContext.NOOP, optimized.beginFieldFetching(mockk(), null))
    }

    @Test
    fun `completion calls every participant and returns the only useful context directly`() {
        val context = mockk<InstrumentationContext<Any>>()
        val participants = listOf(CompletionParticipant(noOp()), CompletionParticipant(context), CompletionParticipant(null))
        val optimized = OptimizedChainedInstrumentation(participants)

        assertSame(context, optimized.beginFieldCompletion(mockk(), null))
        assertEquals(listOf(1, 1, 1), participants.map { it.calls })
        assertSame(noOp<Any>(), OptimizedChainedInstrumentation(listOf(CompletionParticipant(null), CompletionParticipant(noOp()))).beginFieldCompletion(mockk(), null))
    }

    @Test
    fun `completion preserves callback order and errors after filtering no-op contexts`() {
        val first = mockk<InstrumentationContext<Any>>(relaxed = true)
        val second = mockk<InstrumentationContext<Any>>(relaxed = true)
        val optimized = OptimizedChainedInstrumentation(
            listOf(CompletionParticipant(null), CompletionParticipant(first), CompletionParticipant(noOp()), CompletionParticipant(second))
        )
        val result = Any()
        val error = IllegalStateException("completion failed")

        val context = optimized.beginFieldCompletion(mockk(), null)!!
        context.onDispatched()
        context.onCompleted(result, error)

        verifySequence {
            first.onDispatched()
            second.onDispatched()
            first.onCompleted(result, error)
            second.onCompleted(result, error)
        }
    }

    @Test
    fun `completion retains callbacks for participants sharing the same context`() {
        val shared = mockk<InstrumentationContext<Any>>(relaxed = true)
        val optimized = OptimizedChainedInstrumentation(listOf(CompletionParticipant(shared), CompletionParticipant(shared)))

        optimized.beginFieldCompletion(mockk(), null)!!.onCompleted(null, null)

        verify(exactly = 2) { shared.onCompleted(null, null) }
    }

    @Test
    fun `field fetching returns a lone modern context and forwards fetched values`() {
        val delegate = mockk<FieldFetchingInstrumentationContext>(relaxed = true)
        val optimized = OptimizedChainedInstrumentation(
            listOf(FetchingParticipant(null), FetchingParticipant(delegate), FetchingParticipant(FieldFetchingInstrumentationContext.NOOP))
        )
        val result = Any()
        val error = IllegalStateException("fetch failed")

        val context = optimized.beginFieldFetching(mockk(), null)!!
        assertSame(delegate, context)
        context.onDispatched()
        context.onFetchedValue(result)
        context.onCompleted(result, error)

        verifySequence {
            delegate.onDispatched()
            delegate.onFetchedValue(result)
            delegate.onCompleted(result, error)
        }
    }

    @Test
    fun `field fetching discards null and shared no-op modern contexts`() {
        val optimized = OptimizedChainedInstrumentation(
            listOf(FetchingParticipant(FieldFetchingInstrumentationContext.NOOP), FetchingParticipant(null))
        )

        assertSame(FieldFetchingInstrumentationContext.NOOP, optimized.beginFieldFetching(mockk(), null))
    }

    @Test
    fun `shadow execution returns a lone comparison and preserves multiple comparison order`() {
        val events = mutableListOf<String>()
        val first = ShadowFieldExecutionComparison { events += "first" }
        val second = ShadowFieldExecutionComparison { events += "second" }

        fun participant(comparison: ShadowFieldExecutionComparison?) =
            object : ViaductInstrumentationBase(), IViaductInstrumentation.WithShadowFieldExecution {
                override fun requestShadowFieldExecution(
                    parameters: InstrumentationFieldParameters,
                    state: InstrumentationState?
                ): ShadowFieldExecutionComparison? = comparison
            }

        assertSame(first, OptimizedChainedInstrumentation(listOf(participant(first))).requestShadowFieldExecution(mockk(), null))
        assertNull(OptimizedChainedInstrumentation(listOf(participant(null))).requestShadowFieldExecution(mockk(), null))
        assertNull(OptimizedChainedInstrumentation(emptyList()).requestShadowFieldExecution(mockk(), null))
        OptimizedChainedInstrumentation(listOf(participant(first), participant(null), participant(second)))
            .requestShadowFieldExecution(mockk(), null)!!.compare(mockk())
        assertEquals(listOf("first", "second"), events)
    }
}
